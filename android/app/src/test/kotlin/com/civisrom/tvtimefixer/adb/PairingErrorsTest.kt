package com.civisrom.tvtimefixer.adb

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingErrorsTest {
    @Test fun `pairing distinguishes auth deadline TLS protocol and network failures`() {
        assertEquals(ConnectionError.PAIRING_REJECTED, classifyPairingError(PairingRejectedException()))
        assertEquals(ConnectionError.PAIRING_TIMEOUT, classifyPairingError(SocketTimeoutException()))
        assertEquals(ConnectionError.TLS_FAILED, classifyPairingError(SSLException("fixture")))
        assertEquals(ConnectionError.PAIRING_FAILED, classifyPairingError(IOException("peer closed")))
        assertEquals(ConnectionError.UNREACHABLE, classifyPairingError(ConnectException()))
    }
}
