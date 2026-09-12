package com.civisrom.tvtimefixer.adb

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingErrorsTest {
    @Test fun `connection TLS protocol errors are not reported as unreachable devices`() {
        assertEquals(ConnectionError.TLS_FAILED, KadbAdbClientFactory.classify(SSLProtocolException("fixture")))
        assertEquals(ConnectionError.UNREACHABLE, KadbAdbClientFactory.classify(IOException("peer closed")))
    }

    @Test fun `pairing distinguishes auth deadline TLS protocol and network failures`() {
        assertEquals(ConnectionError.PAIRING_REJECTED, classifyPairingError(PairingRejectedException()))
        assertEquals(ConnectionError.PAIRING_TIMEOUT, classifyPairingError(SocketTimeoutException()))
        assertEquals(ConnectionError.TLS_FAILED, classifyPairingError(SSLException("fixture")))
        assertEquals(ConnectionError.PAIRING_FAILED, classifyPairingError(IOException("peer closed")))
        assertEquals(ConnectionError.CONNECTION_REFUSED, classifyPairingError(ConnectException()))
    }
}
