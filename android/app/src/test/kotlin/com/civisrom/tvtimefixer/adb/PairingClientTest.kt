package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PairingClientTest {
    private val exporter: (SSLSocket) -> ByteArray = {
        Conscrypt.exportKeyingMaterial(it, "adb-label\u0000", null, 64)
    }

    @Before fun ephemeralIdentity() {
        KadbCert.configure(InMemoryPrivateKeyStore())
        KadbCert.ensureReady()
    }

    @Test fun `pairing exchanges TLS exporter SPAKE2 and authenticated peer info`() = runBlocking {
        exchange(corrupt = false)
    }

    @Test fun `bad authentication tag is rejected rather than reported unreachable`() = runBlocking {
        try {
            exchange(corrupt = true)
            fail("Corrupted peer info was accepted")
        } catch (e: PairingRejectedException) {
            assertEquals(ConnectionError.PAIRING_REJECTED, classifyPairingError(e))
        }
    }

    private suspend fun exchange(corrupt: Boolean) {
        val context = WirelessFixture.serverContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            WirelessFixture.listener().use { server ->
                val peer = executor.submit { WirelessFixture.pairServer(context, server, corrupt) }
                try {
                    PairingClient(2_000, 3_000, exporter = exporter)
                        .pair(DeviceAddress("127.0.0.1", server.localPort), "123456")
                } finally {
                    peer.get(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `incorrect six digit code never succeeds`() = runBlocking {
        val context = WirelessFixture.serverContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            WirelessFixture.listener().use { server ->
                val peer = executor.submit {
                    try {
                        WirelessFixture.pairServer(context, server, false)
                        fail("Server accepted the wrong code")
                    } catch (_: javax.crypto.AEADBadTagException) {
                        // AOSP likewise cannot decrypt this client's PeerInfo and closes the exchange.
                    }
                }
                try {
                    PairingClient(2_000, 3_000, exporter = exporter)
                        .pair(DeviceAddress("127.0.0.1", server.localPort), "000000")
                    fail("Client accepted the wrong code")
                } catch (e: PairingProtocolException) {
                    assertEquals(ConnectionError.PAIRING_FAILED, classifyPairingError(e))
                } finally { peer.get(5, TimeUnit.SECONDS) }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `overall timeout closes a stalled TLS socket`() = runBlocking {
        WirelessFixture.listener().use { server ->
            val result = async {
                try {
                    PairingClient(2_000, 15_000, overallTimeoutMs = 1_500, exporter = exporter)
                        .pair(DeviceAddress("127.0.0.1", server.localPort), "123456")
                    null
                } catch (e: AdbConnectionException) { e.reason }
            }
            withContext(Dispatchers.IO) {
                server.soTimeout = 3_000
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    // Drain ClientHello. EOF proves cancellation closed the actual socket.
                    while (peer.getInputStream().read() != -1) { }
                }
            }
            assertEquals(ConnectionError.PAIRING_TIMEOUT, withTimeout(3_000) { result.await() })
        }
    }

    @Test fun `caller cancellation closes socket without becoming a connection error`() = runBlocking {
        WirelessFixture.listener().use { server ->
            val job = launch {
                PairingClient(2_000, 15_000, exporter = exporter)
                    .pair(DeviceAddress("127.0.0.1", server.localPort), "123456")
                fail("Cancelled pairing completed")
            }
            val peer = withContext(Dispatchers.IO) {
                server.soTimeout = 3_000
                server.accept()
            }
            peer.use {
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                withContext(Dispatchers.IO) {
                    peer.soTimeout = 3_000
                    while (peer.getInputStream().read() != -1) { }
                }
            }
        }
    }

    @Test fun `socket deadline bounds a stalled handshake before the overall deadline`() = runBlocking {
        WirelessFixture.listener().use { server ->
            val result = async {
                try {
                    PairingClient(2_000, 150, exporter = exporter)
                        .pair(DeviceAddress("127.0.0.1", server.localPort), "123456")
                    fail("Stalled handshake completed")
                } catch (e: SocketTimeoutException) {
                    assertEquals(ConnectionError.PAIRING_TIMEOUT, classifyPairingError(e))
                }
            }
            withContext(Dispatchers.IO) {
                server.soTimeout = 3_000
                server.accept().use { peer ->
                    peer.soTimeout = 3_000
                    while (peer.getInputStream().read() != -1) { }
                }
            }
            withTimeout(3_000) { result.await() }
        }
    }

    @Test fun `malformed pairing header is bounded and classified as protocol failure`() = runBlocking {
        val context = WirelessFixture.serverContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            WirelessFixture.listener().use { server ->
                val peer = executor.submit {
                    server.accept().use { raw ->
                        WirelessFixture.tls(context, raw).use { tls ->
                            val input = java.io.DataInputStream(tls.inputStream)
                            WirelessFixture.readPair(input, 0)
                            val output = java.io.DataOutputStream(tls.outputStream)
                            output.writeByte(1)
                            output.writeByte(0)
                            output.writeInt(Int.MAX_VALUE)
                            output.flush()
                        }
                    }
                }
                try {
                    PairingClient(2_000, 3_000, exporter = exporter)
                        .pair(DeviceAddress("127.0.0.1", server.localPort), "123456")
                    fail("Oversized header accepted")
                } catch (e: PairingProtocolException) {
                    assertEquals(ConnectionError.PAIRING_FAILED, classifyPairingError(e))
                } finally {
                    peer.get(5, TimeUnit.SECONDS)
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `TLS connect uses the pairing identity and executes a real shell probe`() {
        val context = WirelessFixture.serverContext()
        val executor = Executors.newSingleThreadExecutor()
        try {
            WirelessFixture.listener().use { server ->
                val peer = executor.submit { WirelessFixture.connectServer(context, server) }
                KadbAdbClientFactory(2_000, 3_000)
                    .connect(DeviceAddress("127.0.0.1", server.localPort)).close()
                peer.get(5, TimeUnit.SECONDS)
            }
        } finally { executor.shutdownNow() }
    }
}
