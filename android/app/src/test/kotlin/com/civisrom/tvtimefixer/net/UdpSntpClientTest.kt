package com.civisrom.tvtimefixer.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class UdpSntpClientTest {
    private fun elapsed() = System.nanoTime() / 1_000_000

    @Test fun `query falls back from first DNS address to a responding server`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2000
            val worker = thread(isDaemon = true) {
                val request = DatagramPacket(ByteArray(48), 48)
                server.receive(request)
                val response = ByteArray(48)
                response[0] = 0x24; response[1] = 1
                request.data.copyInto(response, 24, 40, 48)
                request.data.copyInto(response, 32, 40, 48)
                request.data.copyInto(response, 40, 40, 48)
                server.send(DatagramPacket(response, response.size, request.address, request.port))
            }
            val client = UdpSntpClient(1200, server.localPort, ::elapsed) {
                arrayOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
            }
            val result = client.query("test.example")
            assertEquals("127.0.0.1", result.address)
            assertNotNull(result.referenceElapsedMillis)
            worker.join(2000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun `system DNS cannot hold the caller beyond the deadline`() {
        val release = CountDownLatch(1)
        val client = UdpSntpClient(150, elapsedRealtime = ::elapsed, resolve = {
            while (release.count > 0) {
                try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            emptyArray()
        })
        try {
            val started = elapsed()
            assertThrows(SocketTimeoutException::class.java) { client.query("test.example") }
            assertTrue(elapsed() - started < 1500)
        } finally { release.countDown() }
    }

    @Test fun `UDP receive observes cancellation without waiting for its whole timeout`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { silent ->
            val client = UdpSntpClient(10_000, silent.localPort, ::elapsed) { arrayOf(InetAddress.getByName("127.0.0.1")) }
            val started = elapsed()
            assertThrows(CancellationException::class.java) {
                client.query("test.example") { if (elapsed() - started > 50) throw CancellationException() }
            }
            assertTrue(elapsed() - started < 1500)
        }
    }
}
