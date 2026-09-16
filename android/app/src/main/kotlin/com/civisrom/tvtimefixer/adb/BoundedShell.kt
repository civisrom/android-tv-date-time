package com.civisrom.tvtimefixer.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import okio.BufferedSource

internal const val SHELL_OUTPUT_LIMIT = 4 * 1024 * 1024
/** Socket reads can ignore thread interruption; cancellation must close the transport. */
internal fun <T> boundedAdbCommand(timeoutMs: Long, close: () -> Unit, block: () -> T): T {
    val pending = FutureTask(block)
    Thread(pending, "adb-command").apply { isDaemon = true; start() }
    try {
        return pending.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        runCatching(close)
        pending.cancel(true)
        throw CancellationException("ADB cancelled", error)
    } catch (_: TimeoutException) {
        runCatching(close)
        pending.cancel(true)
        throw SocketTimeoutException("ADB command deadline")
    } catch (error: ExecutionException) {
        runCatching(close)
        val cause = error.cause ?: error
        if (cause is InterruptedException) throw CancellationException("ADB cancelled", cause)
        throw cause
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
