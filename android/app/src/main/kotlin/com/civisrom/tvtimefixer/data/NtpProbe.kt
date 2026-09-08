package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.NotAnNtpServerException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
) {
    fun test(server: String): NtpProbeResult {
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

        repeat(attempts) {
            try {
                val result = query.query(address)
                rtts += result.rttMs
                offsets += result.offsetSeconds
                if (resolved == null) resolved = result.address.takeIf { it.isNotBlank() }
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
        return NtpProbeResult(
            server = address,
            reachable = true,
            successRate = rtts.size * 100 / attempts,
            avgRttMs = rtts.sum() / rtts.size,
            offsetSeconds = offsets.sum() / offsets.size,
            error = null,
            ipAddress = resolved,
        )
    }

    private companion object {
        const val ERROR_INVALID = "invalid address"
        const val ERROR_UNKNOWN = "unknown error"
    }
}

/**
 * Порядок как в десктопной версии: доля успешных ответов по убыванию, затем
 * средний RTT по возрастанию. Непригодные уходят в конец.
 *
 * Бонус региональным серверам, который есть на десктопе, сюда не перенесён:
 * он опирается на выбранную пользователем страну, а на этом экране её нет.
 */
fun rankNtpServers(results: List<NtpProbeResult>): List<NtpProbeResult> =
    results.sortedWith(
        compareBy<NtpProbeResult> { !it.isUsable() }
            .thenByDescending { it.successRate }
            .thenBy { it.avgRttMs ?: Long.MAX_VALUE },
    )
