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

    @Test fun `stop interrupts blocked input closes transport and preserves partial output`() = runBlocking {
        val reading = CountDownLatch(1)
        var closed = false
        var serviceClosed = false
        var initial = true
        val client = object : AdbClient {
            override fun isAlive() = !closed
            override fun close() { closed = true }
            override fun shell(command: String): ShellResult = error("unexpected")
            override fun openService(destination: String, timeoutMs: Int): AdbService = object : AdbService {
                override val source = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (initial) { initial = false; sink.writeUtf8("partial\n"); return 8 }
                        reading.countDown()
                        CountDownLatch(1).await()
                        return -1
                    }
                }.buffer()
                override val sink = Buffer()
                override fun close() { serviceClosed = true }
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
            assertTrue(closed)
            assertTrue(serviceClosed)
            assertFalse(session.state.value.running)
            assertEquals(TerminalStatus.CANCELLED, session.state.value.status)
            assertEquals("partial\n", session.state.value.output.joinToString("") { it.text })
        } finally { job.cancelAndJoin() }
    }
}
