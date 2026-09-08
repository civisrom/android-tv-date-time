package com.civisrom.tvtimefixer.diagnostics

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.data.NtpProbeFailure
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.device.TimeZoneFailure
import com.civisrom.tvtimefixer.device.TimeZoneRestoration
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OperationTraceTest {
    private fun client(reply: (String) -> ShellResult) = object : AdbClient {
        override fun shell(command: String) = reply(command)
        override fun isAlive() = true
        override fun close() = Unit
    }

    @Test fun `help preserves its real exit code and reports advertised commands`() {
        val trace = OperationTrace()
        val reply = ShellResult("Alarm manager\n  set-timezone TZ\n", "", 255)
        var calls = 0
        assertSame(reply, trace.client(client { calls++; reply }).shell("cmd alarm help"))
        assertEquals(1, calls)
        assertTrue(trace.details().contains("exit=255"))
        assertTrue(trace.details().contains("set-timezone=true"))
    }

    @Test fun `permission failure retains command and code without arguments or raw output`() {
        val trace = OperationTrace()
        val secret = "SECRET_pairing_123456_192.168.1.1_private_key"
        val reply = ShellResult(secret, "SecurityException: Permission denied $secret", 1)
        assertSame(reply, trace.client(client { reply }).shell("settings put global ntp_server '$secret'"))
        val text = trace.details()
        assertTrue(text.contains("shell=settings put global ntp_server <server>"))
        assertTrue(text.contains("exit=1"))
        assertTrue(text.contains("error=permission_denied"))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("192.168.1.1"))
    }

    @Test fun `getprop reports API without serials or other properties`() {
        val trace = OperationTrace()
        trace.client(client { ShellResult("[ro.build.version.sdk]: [30]\n[ro.serialno]: [SECRET_SERIAL]\n", "", 0) })
            .shell("getprop")
        assertTrue(trace.details().contains("target_api=30"))
        assertFalse(trace.details().contains("SECRET_SERIAL"))
        assertFalse(trace.details().contains("ro.serialno"))
    }

    @Test fun `exception keeps identity and class but excludes its message and unknown command`() {
        val trace = OperationTrace()
        val error = IOException("SECRET_MESSAGE")
        try {
            trace.client(client { throw error }).shell("echo SECRET_ARGUMENT")
            fail("Expected the same exception")
        } catch (actual: IOException) {
            assertSame(error, actual)
        }
        assertTrue(trace.details().contains("shell=other"))
        assertTrue(trace.details().contains("java.io.IOException"))
        assertFalse(trace.details().contains("SECRET"))
    }

    @Test fun `NTP failures retain classification and metrics without submitted addresses`() {
        val trace = OperationTrace()
        trace.ntp(NtpProbeResult("SECRET_HOST", false, 0, null, null, "SECRET_ERROR",
            ipAddress = "192.168.1.1", failure = NtpProbeFailure.DNS))
        assertTrue(trace.details().contains("failure=DNS"))
        assertTrue(trace.details().contains("success_pct=0"))
        assertFalse(trace.details().contains("SECRET"))
        assertFalse(trace.details().contains("192.168.1.1"))
    }

    @Test fun `bounded trace retains final restoration and survives journal restart`() {
        val directory = Files.createTempDirectory("operation-trace").toFile()
        try {
            val trace = OperationTrace()
            val client = trace.client(client { ShellResult("false", "", 0) })
            repeat(100) { client.shell("cmd time_zone_detector is_auto_detection_enabled") }
            trace.timeZone(TimeZoneUpdateResult.Failed(TimeZoneFailure.WRITE, TimeZoneRestoration.UNCONFIRMED))
            assertTrue(trace.details().length <= 2048)
            assertTrue(trace.details().contains("trace.truncated=true"))
            DiagnosticJournal(directory).use {
                it.record(Operation.APPLY_TIME_ZONE, Outcome.FAILED, reason = ConnectionError.USB_IO, trace = trace)
                it.awaitIdle()
            }
            DiagnosticJournal(directory).use {
                it.awaitIdle()
                val details = diagnosticDetails(it.snapshot.value.events.single())
                assertTrue(details.contains("restoration=UNCONFIRMED"))
                assertTrue(details.contains("operation=APPLY_TIME_ZONE"))
                assertTrue(details.contains("reason=USB_IO"))
            }
        } finally { directory.deleteRecursively() }
    }
}
