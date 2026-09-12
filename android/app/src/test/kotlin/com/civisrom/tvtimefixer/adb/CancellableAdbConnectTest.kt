package com.civisrom.tvtimefixer.adb

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CancellableAdbConnectTest {
    @Test fun `cancellation closes a real socket blocked waiting for authorization`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 2_000
            Socket().use { socket ->
                val error = AtomicReference<Throwable>()
                val finished = CountDownLatch(1)
                val workerStopped = CountDownLatch(1)
                val caller = thread {
                    try {
                        cancellableAdbConnect(30_000, socket::close) {
                            try {
                                socket.connect(listener.localSocketAddress, 2_000)
                                socket.getInputStream().read()
                            } finally { workerStopped.countDown() }
                        }
                    } catch (caught: Throwable) { error.set(caught)
                    } finally { finished.countDown() }
                }
                listener.accept().use { peer ->
                    peer.soTimeout = 2_000
                    caller.interrupt()
                    assertTrue(finished.await(2, TimeUnit.SECONDS))
                    assertTrue(error.get() is CancellationException)
                    assertEquals(-1, peer.getInputStream().read())
                    assertTrue(workerStopped.await(2, TimeUnit.SECONDS))
                }
                caller.join(2_000)
            }
        }
    }

    @Test fun `deadline closes a stalled handshake but success retains the connection`() {
        val ended = CountDownLatch(1)
        val closed = CountDownLatch(1)
        assertThrows(SocketTimeoutException::class.java) {
            cancellableAdbConnect(100, { closed.countDown() }) {
                try { closed.await() } finally { ended.countDown() }
            }
        }
        assertTrue(ended.await(2, TimeUnit.SECONDS))
        assertEquals(42, cancellableAdbConnect(2_000, { fail("Successful connection closed") }) { 42 })
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
