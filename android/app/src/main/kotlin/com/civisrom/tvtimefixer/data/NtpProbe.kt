package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.SntpQuery
import kotlin.math.abs

/** Итог проверки одного сервера времени. */
data class NtpProbeResult(
    val server: String,
    val reachable: Boolean,
    /** Доля успешных ответов, 0…100. */
    val successRate: Int,
    val avgRttMs: Long?,
    val offsetSeconds: Double?,
    val error: String?,
)

/**
 * Предел расхождения часов, при котором сервер считается пригодным.
 *
 * Ровно то же число, что и в десктопной версии (`abs(avg_offset) > 60`
 * отвергает сервер). Расходиться нельзя: адрес, принятый на телефоне, должен
 * приниматься и на компьютере.
 */
const val MAX_OFFSET_SECONDS = 60.0

/**
 * Годен ли сервер к применению.
 *
 * Недостаточно того, что адрес ответил: сервер обязан сообщить время, близкое
 * к настоящему. Иначе телевизор получит адрес, который «работает», но часы по
 * нему уедут — а заметит это человек очень нескоро.
 */
fun NtpProbeResult.isUsable(): Boolean {
    val offset = offsetSeconds ?: return false
    return reachable && abs(offset) <= MAX_OFFSET_SECONDS
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
            return NtpProbeResult(address, false, 0, null, null, ERROR_INVALID)
        }

        val rtts = mutableListOf<Long>()
        val offsets = mutableListOf<Double>()
        var lastError: String? = null

        repeat(attempts) {
            try {
                val result = query.query(address)
                rtts += result.rttMs
                offsets += result.offsetSeconds
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        if (rtts.isEmpty()) {
            return NtpProbeResult(address, false, 0, null, null, lastError ?: ERROR_UNKNOWN)
        }
        return NtpProbeResult(
            server = address,
            reachable = true,
            successRate = rtts.size * 100 / attempts,
            avgRttMs = rtts.sum() / rtts.size,
            offsetSeconds = offsets.sum() / offsets.size,
            error = null,
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
