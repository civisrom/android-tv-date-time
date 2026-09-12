package com.civisrom.tvtimefixer.adb

import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

/** Interrupting Socket.read is insufficient; the caller owns and closes the pending socket. */
internal fun <T> cancellableAdbConnect(timeoutMs: Long, close: () -> Unit, connect: () -> T): T {
    val pending = FutureTask(connect)
    Thread(pending, "adb-connect").apply { isDaemon = true; start() }
    try {
        return pending.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (cancelled: InterruptedException) {
        runCatching(close)
        pending.cancel(true)
        throw CancellationException("ADB connection cancelled", cancelled)
    } catch (_: TimeoutException) {
        runCatching(close)
        pending.cancel(true)
        throw SocketTimeoutException("ADB authentication deadline")
    } catch (error: ExecutionException) {
        runCatching(close)
        throw (error.cause ?: error)
    }
}
