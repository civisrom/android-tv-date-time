package com.civisrom.tvtimefixer.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okio.BufferedSource

internal const val SHELL_OUTPUT_LIMIT = 4 * 1024 * 1024
private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "adb-command-deadline").apply { isDaemon = true }
}

/** Kadb Source.timeout() == NONE; interrupt также прерывает его ожидание очереди. */
internal fun <T> boundedAdbCommand(timeoutMs: Long, close: () -> Unit, block: () -> T): T {
    val owner = Thread.currentThread()
    val lock = Any()
    var active = true
    var expired = false
    val timer = deadlines.schedule({ synchronized(lock) {
        if (active) { expired = true; owner.interrupt() }
    } }, timeoutMs, TimeUnit.MILLISECONDS)
    try {
        return block().also { synchronized(lock) { if (expired) throw SocketTimeoutException("ADB command deadline") } }
    } catch (error: Exception) {
        val interrupted = Thread.interrupted()
        runCatching(close)
        if (synchronized(lock) { expired }) throw SocketTimeoutException("ADB command deadline")
        if (interrupted || error is InterruptedException) throw CancellationException("ADB cancelled", error)
        throw error
    } finally {
        synchronized(lock) {
            active = false
            timer.cancel(false)
            if (expired) Thread.interrupted()
        }
    }
}

/** Проверяет размер кадра до выделения памяти; UTF-8 декодируется после сборки байтов. */
internal fun readBoundedShell(source: BufferedSource, v2: Boolean, limit: Int = SHELL_OUTPUT_LIMIT): ShellResult {
    val output = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    var remaining = limit
    if (!v2) {
        val buffer = ByteArray(4096)
        while (true) {
            val read = source.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (read == -1) break
            if (read > remaining) throw IOException("ADB output limit exceeded")
            output.write(buffer, 0, read)
            remaining -= read
        }
        return ShellResult(output.toString("UTF-8"), "", 0)
    }
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val id = source.readByte().toInt()
        val length = source.readIntLe()
        if (id !in 1..3 || length < 0 || (id == 3 && length != 1) || (id != 3 && length > remaining)) {
            throw IOException("Invalid or oversized ADB shell frame")
        }
        if (id == 3) return ShellResult(output.toString("UTF-8"), errors.toString("UTF-8"),
            source.readByte().toInt() and 0xff)
        val bytes = source.readByteArray(length.toLong())
        if (id == 1) output.write(bytes) else errors.write(bytes)
        remaining -= length
    }
}
