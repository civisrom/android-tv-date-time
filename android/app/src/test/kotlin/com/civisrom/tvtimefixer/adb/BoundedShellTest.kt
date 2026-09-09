package com.civisrom.tvtimefixer.adb

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class BoundedShellTest {
    @Test fun `shell v2 handles fragmented UTF8 and stderr with an exact exit code`() {
        val bytes = "Привет".toByteArray()
        val source = Buffer()
        for (byte in bytes) source.writeByte(1).writeIntLe(1).writeByte(byte.toInt())
        source.writeByte(2).writeIntLe(3).writeUtf8("err").writeByte(3).writeIntLe(1).writeByte(17)
        assertEquals(ShellResult("Привет", "err", 17), readBoundedShell(source, true))
    }

    @Test fun `rejects huge frame lengths before reading their bodies`() {
        for (size in listOf(-1, Int.MAX_VALUE, SHELL_OUTPUT_LIMIT + 1)) {
            val source = Buffer().writeByte(1).writeIntLe(size)
            assertThrows(IOException::class.java) { readBoundedShell(source, true) }
        }
    }

    @Test fun `aggregate stdout and stderr are bounded and legacy never reads unbounded text`() {
        val source = Buffer().writeByte(1).writeIntLe(3).writeUtf8("abc")
            .writeByte(2).writeIntLe(3).writeUtf8("def")
        assertThrows(IOException::class.java) { readBoundedShell(source, true, 5) }
        assertThrows(IOException::class.java) { readBoundedShell(Buffer().writeUtf8("123456"), false, 5) }
        assertEquals("12345", readBoundedShell(Buffer().writeUtf8("12345"), false, 5).output)
    }

    @Test fun `deadline interrupts a stalled command closes transport and clears worker interrupt`() {
        var closed = false
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            boundedAdbCommand(100, { closed = true }) { CountDownLatch(1).await() }
        }
        assertTrue(closed)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(42, boundedAdbCommand(500, { fail("Successful command closed") }) { 42 })
    }
}
