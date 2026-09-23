package com.civisrom.tvtimefixer.diagnostics

import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportTest {
    @Test fun `export contains useful statuses but never private event details or clock addresses`() {
        val secret = "192.0.2.123 private.example raw-command secret pairing123456 serial-identity"
        val report = diagnosticExport(DiagnosticExportState("2.6.5", 36, 34, DiagnosticTransport.NETWORK, true,
            clock = DeviceTimeCheck(DeviceTimeStatus.MATCH, server = secret, deviceTimeMillis = 123456789000L,
                differenceSeconds = 0.125, uncertaintySeconds = 0.7, timeZoneId = secret, observedAtElapsedMillis = 0)),
            DiagnosticSnapshot(events = listOf(DiagnosticEvent(987654321, 123456789000L,
                Operation.APPLY_NTP, Outcome.FAILED, details = secret, issue = DiagnosticIssue.NTP_PERMISSION_DENIED))))
        for (privateValue in secret.split(' ') + listOf("987654321", "123456789000")) assertFalse(privateValue, report.contains(privateValue))
        assertTrue(report.contains("clock_comparison=MATCH"))
        assertTrue(report.contains("clock_difference_seconds=0.125"))
        assertTrue(report.contains("issue=NTP_PERMISSION_DENIED"))
        assertTrue(report.contains("clock_source_current=unconfirmed"))
    }

    @Test fun `fresh failed attempts retain their precise failure category instead of becoming stale`() {
        for (status in listOf(DeviceTimeStatus.SYSTEM_DEFAULT, DeviceTimeStatus.NO_SERVER,
            DeviceTimeStatus.NTP_UNAVAILABLE, DeviceTimeStatus.DEVICE_UNAVAILABLE)) {
            val report = diagnosticExport(DiagnosticExportState("2.6.5", 36,
                clock = DeviceTimeCheck(status, observedAtElapsedMillis = 1000), elapsedNow = 1001), DiagnosticSnapshot())
            assertTrue(report.contains("clock_comparison=$status"))
            assertFalse(report.contains("clock_comparison=STALE"))
            assertFalse(report.contains("clock_difference_seconds="))
        }
    }

    @Test fun `old or undated measurement is explicitly stale`() {
        for (check in listOf(DeviceTimeCheck(DeviceTimeStatus.MATCH), DeviceTimeCheck(DeviceTimeStatus.MATCH, observedAtElapsedMillis = 1))) {
            val report = diagnosticExport(DiagnosticExportState("2.6.5", 36, clock = check, elapsedNow = 31_002), DiagnosticSnapshot())
            assertTrue(report.contains("clock_last_comparison=MATCH"))
            assertTrue(report.contains("clock_comparison=STALE"))
        }
    }

    @Test fun `export bounds events and rejects arbitrary version and nonfinite numeric data`() {
        val events = List(500) { DiagnosticEvent(it.toLong(), 0, Operation.TERMINAL, Outcome.SUCCESS, details = "never-copy") }
        val report = diagnosticExport(DiagnosticExportState("192.0.2.1", -1, clock =
            DeviceTimeCheck(DeviceTimeStatus.UNCERTAIN, differenceSeconds = Double.NaN)), DiagnosticSnapshot(events))
        assertEquals(100, report.lineSequence().count { it.startsWith("event=") })
        assertFalse(report.contains("192.0.2.1"))
        assertFalse(report.contains("never-copy"))
        assertFalse(report.contains("NaN"))
    }
}
