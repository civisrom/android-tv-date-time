package com.civisrom.tvtimefixer.terminal

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.AdbService
import com.civisrom.tvtimefixer.adb.ShellResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `stop preserves multiplexed connection and closes non multiplexed transport without an IO error`() = runBlocking {
        for (independent in listOf(false, true)) checkCancellation(independent)
    }

    private suspend fun checkCancellation(independent: Boolean) = coroutineScope {
        val reading = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        var closed = false
        var serviceClosed = false
        var initial = true
        val client = object : AdbClient {
            override val independentServiceClose = independent
            override fun isAlive() = !closed
            override fun close() { closed = true; stopped.countDown() }
            override fun shell(command: String): ShellResult {
                check(!closed)
                return ShellResult("still connected", "", 0)
            }
            override fun openService(destination: String, timeoutMs: Int): AdbService = object : AdbService {
                override val source = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (initial) { initial = false; sink.writeUtf8("partial\n"); return 8 }
                        reading.countDown()
                        stopped.await()
                        return -1
                    }
                }.buffer()
                override val sink = Buffer()
                override fun close() { serviceClosed = true; stopped.countDown(); inputClosed.countDown() }
            }
        }
        val session = TerminalSession().apply { edit("logcat"); start("TV") }
        val job = launch(Dispatchers.Default) {
            try {
                runInterruptible(Dispatchers.IO) {
                    TerminalExecutor(TerminalFiles(temporary.root), session).execute(client, TerminalCommand.Shell("logcat"))
                }
                fail("Stopped command returned success")
            } catch (e: CancellationException) { session.fail(e); throw e }
        }
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(!independent, closed)
            assertTrue(inputClosed.await(1, TimeUnit.SECONDS))
            assertTrue(serviceClosed)
            assertFalse(session.state.value.running)
            assertEquals(TerminalStatus.CANCELLED, session.state.value.status)
            assertNull(session.state.value.problem)
            assertEquals("partial\n", session.state.value.output.joinToString("") { it.text })
            if (independent) assertEquals("still connected", client.shell("echo").trimmedOutput)
        } finally { job.cancelAndJoin() }
    }
}
