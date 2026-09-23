package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.SntpResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class ClockMonitorCancellationTest {
    @Test fun `stop interrupts blocked ADB measurement releases mutex and publishes no late sample`() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = object : AdbClient {
            override fun shell(command: String): ShellResult {
                calls.incrementAndGet(); entered.countDown()
                try { Thread.sleep(30_000) } catch (error: InterruptedException) { interrupted.countDown(); throw error }
                return ShellResult("pool.ntp.org", "", 0)
            }
            override fun isAlive() = true
            override fun close() = Unit
        }
        val query = object : SntpQuery { override fun query(host: String): SntpResult = error("No NTP after cancellation") }
        val verifier = DeviceTimeVerifier(query) { 0 }
        val monitor = ClockMonitor { 0 }
        val mutex = Mutex()
        monitor.start()
        val job = launch(Dispatchers.Default) {
            mutex.lock()
            try {
                val check = runInterruptible(Dispatchers.IO) { verifier.verify(client) }
                if (monitor.state.running) monitor.add(check)
            } finally { mutex.unlock() }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            monitor.stop(ClockMonitorEnd.USER)
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
            assertFalse(mutex.isLocked)
            assertTrue(monitor.state.samples.isEmpty())
            assertEquals(ClockMonitorEnd.USER, monitor.state.ended)
            assertEquals(1, calls.get())
            assertTrue(mutex.tryLock()); mutex.unlock()
        } finally { job.cancelAndJoin() }
    }
}
