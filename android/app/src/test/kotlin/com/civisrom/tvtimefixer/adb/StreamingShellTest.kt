package com.civisrom.tvtimefixer.adb

import java.io.IOException
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StreamingShellTest {
    private fun frame(id: Int, bytes: ByteArray): ByteArray =
        Buffer().writeByte(id).writeIntLe(bytes.size).write(bytes).readByteArray()

    @Test fun `every byte boundary preserves multibyte UTF8 stdout stderr and exit`() {
        val wire = frame(1, "Привет 🌍\n".toByteArray()) + frame(2, "Ошибка\n".toByteArray()) + frame(3, byteArrayOf(255.toByte()))
        for (split in 1..wire.size) {
            val out = StringBuilder(); val err = StringBuilder()
            val stream = StreamingShell(true) { text, error -> (if (error) err else out).append(text) }
            wire.asList().chunked(split).forEach { stream.accept(it.toByteArray()) }
            assertEquals(255, stream.finish())
            assertEquals("Привет 🌍\n", out.toString())
            assertEquals("Ошибка\n", err.toString())
        }
    }

    @Test fun `legacy output has unknown exit status and handles incomplete UTF8 at EOF`() {
        val text = StringBuilder()
        val stream = StreamingShell(false) { value, _ -> text.append(value) }
        "Привет".toByteArray().forEach { stream.accept(byteArrayOf(it)) }
        stream.accept(byteArrayOf(0xd0.toByte()))
        assertNull(stream.finish())
        assertEquals("Привет�", text.toString())
    }

    @Test fun `large output streams without a total memory or frame accumulation`() {
        var count = 0
        val stream = StreamingShell(true) { text, _ -> count += text.length }
        val bytes = frame(1, ByteArray(4000) { 'a'.code.toByte() })
        repeat(3000) { stream.accept(bytes) }
        stream.accept(frame(3, byteArrayOf(0)))
        assertEquals(0, stream.finish())
        assertEquals(12_000_000, count)
    }

    @Test fun `truncated invalid and trailing shell frames are rejected`() {
        val invalid = listOf(
            byteArrayOf(1), frame(1, "abc".toByteArray()),
            Buffer().writeByte(1).writeIntLe(Int.MAX_VALUE).readByteArray(),
            Buffer().writeByte(2).writeIntLe(-1).readByteArray(),
            frame(7, byteArrayOf()), frame(3, byteArrayOf(0, 0)),
            frame(3, byteArrayOf(0)) + frame(1, byteArrayOf(1)),
        )
        invalid.forEach { wire ->
            assertThrows(IOException::class.java) {
                val stream = StreamingShell(true) { _, _ -> }
                stream.accept(wire); stream.finish()
            }
        }
    }

    @Test fun `source reader stops at exit frame without waiting for transport EOF`() {
        val source = Buffer().write(frame(1, "done".toByteArray())).write(frame(3, byteArrayOf(4)))
        val out = StringBuilder()
        assertEquals(4, readStreamingShell(source, true) { text, _ -> out.append(text) })
        assertEquals("done", out.toString())
    }
}
