package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.*
import org.junit.Test

class TimeSourceSafetyTest {
    private fun shell(text: String) = ShellResult(text, "", 0)

    @Test fun `a newer unknown clock origin does not leave the old network origin displayed as last`() {
        val evidence = parseTimeSourceEvidence(null, shell("""
            mLastAutoSystemClockTimeSet=UnixEpochTime{...}
            PT1H / 2026-09-23T12:00:00Z - Set system clock & confidence. origin=network newTime=...
            PT2H / 2026-09-23T13:00:00Z - Set system clock & confidence. origin=vendor_source newTime=...
        """.trimIndent()))
        assertEquals(TimeSourceReadStatus.AVAILABLE, evidence.detectorStatus)
        assertNull(evidence.lastRecordedClockOrigin)
    }

    @Test fun `newer confidence only update does not replace the last historical clock write`() {
        val evidence = parseTimeSourceEvidence(null, shell("""
            mLastAutoSystemClockTimeSet=UnixEpochTime{...}
            PT1H / 2026-09-23T12:00:00Z - Set system clock & confidence. origin=telephony newTime=...
            PT2H / 2026-09-23T13:00:00Z - Set system clock confidence. origin=network newTime=...
        """.trimIndent()))
        assertEquals(RecordedClockOrigin.TELEPHONY, evidence.lastRecordedClockOrigin)
        assertNull(evidence.lastResponseUri)
    }

    @Test fun `permission failure containing otherwise recognizable evidence cannot claim a source`() {
        val detector = ShellResult("""
            Permission Denial
            mLastAutoSystemClockTimeSet=UnixEpochTime{...}
            PT1H / 2026-09-23T12:00:00Z - Set system clock & confidence. origin=network newTime=...
        """.trimIndent(), "", 0)
        val evidence = parseTimeSourceEvidence(null, detector)
        assertEquals(TimeSourceReadStatus.PERMISSION_DENIED, evidence.detectorStatus)
        assertNull(evidence.lastRecordedClockOrigin)
    }

    @Test fun `unsafe URI and negative age are not reported as usable response evidence`() {
        val unsafe = parseTimeSourceEvidence(shell("""
            NtpTrustedTime:
            mLastSuccessfulNtpServerUri=ntp://user:password@private.example
            mTimeResult.getAgeMillis()=PT1S
        """.trimIndent()), null)
        assertNull(unsafe.lastResponseUri)
        assertNull(unsafe.responseAgeMillis)
        val invalidAge = parseTimeSourceEvidence(shell("""
            NtpTrustedTime:
            mLastSuccessfulNtpServerUri=ntp://historical.example
            mTimeResult.getAgeMillis()=-1
        """.trimIndent()), null)
        assertEquals("ntp://historical.example", invalidAge.lastResponseUri)
        assertNull(invalidAge.responseAgeMillis)
    }
}
