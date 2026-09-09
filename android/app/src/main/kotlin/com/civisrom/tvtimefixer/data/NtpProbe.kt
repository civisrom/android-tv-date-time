package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.NotAnNtpServerException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

enum class NtpProbeFailure { INVALID_ADDRESS, DNS, TIMEOUT, INVALID_RESPONSE, NETWORK }

/** Итог проверки одного сервера времени. */
data class NtpProbeResult(
    val server: String,
    val reachable: Boolean,
    /** Доля успешных ответов, 0…100. */
    val successRate: Int,
    val avgRttMs: Long?,
    val offsetSeconds: Double?,
    val error: String?,
    /** IP, к которому обратились. У адреса, введённого как IP, совпадает с ним. */
    val ipAddress: String? = null,
    val failure: NtpProbeFailure? = null,
    val medianRttMs: Long? = null,
    val rttJitterMs: Double? = null,
)

/**
 * Получен корректный NTP-ответ. Величина смещения не ограничивается:
 * локальные часы могут быть сбиты, и сервер выбирают для их исправления.
 */
fun NtpProbeResult.isUsable(): Boolean {
    val offset = offsetSeconds ?: return false
    return reachable && offset.isFinite()
}

/**
 * Проверяет сервер несколькими попытками.
 *
 * Повторяет `_test_ntp_server` из десктопной половины: несколько запросов,
 * средний RTT, доля успешных ответов, среднее смещение. Одна попытка ничего не
 * говорит о надёжности — сервер может ответить и пропасть.
 */
class NtpProbe(
    private val query: SntpQuery,
    private val attempts: Int = 2,
    private val pause: () -> Unit = {},
) {
    init { require(attempts > 0) }

    fun test(server: String, checkCancelled: () -> Unit = {}): NtpProbeResult {
        val address = server.trim()
        if (!isValidNtpServer(address)) {
            return NtpProbeResult(address, false, 0, null, null, ERROR_INVALID,
                failure = NtpProbeFailure.INVALID_ADDRESS)
        }

        val rtts = mutableListOf<Long>()
        val offsets = mutableListOf<Double>()
        var lastError: String? = null
        var failure: NtpProbeFailure? = null
        var resolved: String? = null

        repeat(attempts) { attempt ->
            checkCancelled()
            if (attempt > 0) pause()
            checkCancelled()
            try {
                val result = query.query(address, checkCancelled)
                if (result.rttMs < 0 || !result.offsetSeconds.isFinite()) {
                    throw NotAnNtpServerException("Invalid NTP measurement")
                }
                rtts += result.rttMs
                offsets += result.offsetSeconds
                if (resolved == null) resolved = result.address.takeIf { it.isNotBlank() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                throw CancellationException("NTP cancelled", e)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                failure = when (e) {
                    is UnknownHostException -> NtpProbeFailure.DNS
                    is SocketTimeoutException -> NtpProbeFailure.TIMEOUT
                    is NotAnNtpServerException -> NtpProbeFailure.INVALID_RESPONSE
                    else -> NtpProbeFailure.NETWORK
                }
            }
        }

        if (rtts.isEmpty()) {
            return NtpProbeResult(address, false, 0, null, null, lastError ?: ERROR_UNKNOWN,
                failure = failure ?: NtpProbeFailure.NETWORK)
        }
        val sorted = rtts.sorted()
        val median = sorted[sorted.size / 2] / 2.0 + sorted[(sorted.size - 1) / 2] / 2.0
        val jitter = sqrt(rtts.map { (it - median) * (it - median) }.average())
        return NtpProbeResult(
            server = address,
            reachable = true,
            successRate = rtts.size * 100 / attempts,
            avgRttMs = rtts.sum() / rtts.size,
            offsetSeconds = offsets.sum() / offsets.size,
            error = null,
            ipAddress = resolved,
            medianRttMs = median.toLong(),
            rttJitterMs = jitter,
        )
    }

    private companion object {
        const val ERROR_INVALID = "invalid address"
        const val ERROR_UNKNOWN = "unknown error"
    }
}

/**
 * Сначала доступность, затем медиана задержки с добавлением её разброса (RMS).
 * Единичный быстрый ответ не делает нестабильный сервер лучшим. Это оценка
 * качества соединения, а не гарантия абсолютной точности часов сервера.
 */
fun rankNtpServers(results: List<NtpProbeResult>): List<NtpProbeResult> =
    results.sortedWith(
        compareBy<NtpProbeResult> { !it.isUsable() }
            .thenByDescending { it.successRate }
            .thenBy { (it.medianRttMs ?: it.avgRttMs)?.toDouble()?.plus(it.rttJitterMs ?: 0.0)
                ?: Double.POSITIVE_INFINITY },
    )
