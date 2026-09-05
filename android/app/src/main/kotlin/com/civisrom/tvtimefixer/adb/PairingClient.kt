package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import javax.net.ssl.SSLSocket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PairingClient(
    private val connectTimeoutMs: Int,
    private val socketTimeoutMs: Int,
    private val overallTimeoutMs: Long = 60_000,
    private val exporter: (SSLSocket) -> ByteArray,
) {
    suspend fun pair(address: DeviceAddress, code: String) {
        val completed = withTimeoutOrNull(overallTimeoutMs) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    PairingSession(address.host, address.port, code, connectTimeoutMs, socketTimeoutMs, exporter).use { session ->
                        continuation.invokeOnCancellation { session.close() }
                        try {
                            session.start()
                            continuation.resume(Unit)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            }
            true
        }
        if (completed == null) throw AdbConnectionException(ConnectionError.PAIRING_TIMEOUT)
    }
}
