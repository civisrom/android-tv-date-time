package com.civisrom.tvtimefixer.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import okio.Buffer
import okio.BufferedSource

/** Декодирует разорванные UTF-8 последовательности отдельно для stdout и stderr. */
private class ShellTextDecoder(private val emit: (String) -> Unit) {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteBuffer.allocate(8192)
    private val chars = CharBuffer.allocate(8192)

    fun accept(value: ByteArray, end: Boolean = false) {
        bytes.put(value).flip()
        decoder.decode(bytes, chars, end).throwExceptionIfError()
        bytes.compact()
        if (end) decoder.flush(chars).throwExceptionIfError()
        chars.flip()
        if (chars.hasRemaining()) emit(chars.toString())
        chars.clear()
    }

    private fun java.nio.charset.CoderResult.throwExceptionIfError() { if (isError) throwException() }
}

/** Не накапливает вывод или целые shell_v2 кадры в памяти. */
internal class StreamingShell(private val v2: Boolean, emit: (String, Boolean) -> Unit) {
    private val stdout = ShellTextDecoder { emit(it, false) }
    private val stderr = ShellTextDecoder { emit(it, true) }
    private val buffer = Buffer()
    private var channel = 0
    private var remaining = 0
    var exitCode: Int? = null
        private set
    val completed: Boolean get() = exitCode != null

    fun accept(bytes: ByteArray) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        require(bytes.size <= 4096)
        if (!v2) { stdout.accept(bytes); return }
        buffer.write(bytes)
        while (buffer.size > 0) {
            if (completed) throw IOException("Data after shell exit")
            if (remaining == 0) {
                if (buffer.size < 5) return
                channel = buffer.readByte().toInt()
                remaining = buffer.readIntLe()
                if (channel !in 1..3 || remaining < 0 || remaining > SHELL_OUTPUT_LIMIT ||
                    (channel == 3 && remaining != 1)) throw IOException("Invalid shell frame")
                if (remaining == 0) continue
            }
            val count = minOf(remaining.toLong(), buffer.size).toInt()
            if (count == 0) return
            val payload = buffer.readByteArray(count.toLong())
            when (channel) {
                1 -> stdout.accept(payload)
                2 -> stderr.accept(payload)
                3 -> exitCode = payload[0].toInt() and 255
            }
            remaining -= count
        }
    }

    fun finish(): Int? {
        if (v2 && (exitCode == null || remaining != 0 || buffer.size != 0L)) throw IOException("Incomplete shell response")
        stdout.accept(byteArrayOf(), true)
        stderr.accept(byteArrayOf(), true)
        return exitCode
    }
}

internal fun readStreamingShell(source: BufferedSource, v2: Boolean, emit: (String, Boolean) -> Unit): Int? {
    val reader = StreamingShell(v2, emit)
    val buffer = ByteArray(4096)
    while (!reader.completed) {
        val count = source.read(buffer)
        if (count == -1) break
        reader.accept(buffer.copyOf(count))
    }
    return reader.finish()
}
