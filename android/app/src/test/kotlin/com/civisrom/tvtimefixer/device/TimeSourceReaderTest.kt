package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.*
import org.junit.Test

class TimeSourceReaderTest {
    private fun shell(text: String) = ShellResult(text, "", 0)
    private val cache = shell("""
        NtpTrustedTime:
          mLastSuccessfulNtpServerUri=ntp://historical.example
          mTimeResult.getAgeMillis()=PT53M21.657S
    """.trimIndent())

    @Test fun `a successful cached NTP response and confidence update do not prove a clock write`() {
        val evidence = parseTimeSourceEvidence(cache, shell("""
            mLastAutoSystemClockTimeSet=null
            PT59H45M24.046S / 2026-09-23T16:42:51Z - Set system clock confidence. origin=network newTime=...
        """.trimIndent()))
        assertEquals("ntp://historical.example", evidence.lastResponseUri)
        assertEquals(3_201_657L, evidence.responseAgeMillis)
        assertNull(evidence.lastRecordedClockOrigin)
    }

    @Test fun `an explicit historical clock write does not get attributed to an unrelated cache URI`() {
        val evidence = parseTimeSourceEvidence(cache, shell("""
            mLastAutoSystemClockTimeSet=UnixEpochTime{...}
            PT59H45M24.046S / 2026-09-23T16:42:51Z - Set system clock & confidence. origin=telephony newTime=...
        """.trimIndent()))
        assertEquals(RecordedClockOrigin.TELEPHONY, evidence.lastRecordedClockOrigin)
        assertEquals("ntp://historical.example", evidence.lastResponseUri)
    }

    @Test fun `permission errors absent services and unknown vendor output stay distinct`() {
        val denied = parseTimeSourceEvidence(ShellResult("Permission Denial", "", 0), shell("Can't find service: time_detector"))
        assertEquals(TimeSourceReadStatus.PERMISSION_DENIED, denied.networkStatus)
        assertEquals(TimeSourceReadStatus.UNSUPPORTED, denied.detectorStatus)
        val vendor = parseTimeSourceEvidence(shell("unknown OEM output ntp://not-proof.example"), null)
        assertEquals(TimeSourceReadStatus.UNPARSEABLE, vendor.networkStatus)
        assertNull(vendor.lastResponseUri)
        assertEquals(TimeSourceReadStatus.UNAVAILABLE, vendor.detectorStatus)
    }
}
