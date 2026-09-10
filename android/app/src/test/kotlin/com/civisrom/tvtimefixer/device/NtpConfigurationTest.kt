package com.civisrom.tvtimefixer.device

import org.junit.Assert.*
import org.junit.Test

class NtpConfigurationTest {
    @Test fun `system default is distinct from unavailable configuration`() {
        assertTrue(NtpConfiguration("null").systemDefault)
        assertFalse(NtpConfiguration("").systemDefault)
        assertTrue(NtpConfiguration("null").endpoints.isEmpty())
    }

    @Test fun `reads legacy servers and modern URIs with custom ports and IPv6`() {
        assertEquals(listOf(NtpEndpoint("pool.ntp.org")), NtpConfiguration("pool.ntp.org").endpoints)
        assertEquals(listOf(NtpEndpoint("pool.ntp.org", 1123), NtpEndpoint("2001:db8::1")),
            NtpConfiguration("ntp://pool.ntp.org:1123|ntp://[2001:db8::1]").endpoints)
        for (raw in listOf("https://pool.ntp.org", "ntp://user@pool.ntp.org", "ntp://pool.ntp.org/path",
                "ntp://pool.ntp.org:0", "ntp://pool.ntp.org:65536", "ntp://pool.ntp.org?query", "garbage")) {
            assertTrue(raw, NtpConfiguration(raw).endpoints.isEmpty())
        }
    }

    @Test fun `Android 6 through 10 report cached system service configuration`() {
        for (api in 23..29) assertEquals(NtpActivation.RESTART_REQUIRED, ntpActivation(api))
        for (api in 30..37) assertEquals(NtpActivation.NEXT_REFRESH, ntpActivation(api))
        assertEquals(NtpActivation.UNKNOWN, ntpActivation(null))
    }
}
