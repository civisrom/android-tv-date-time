package com.civisrom.tvtimefixer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Ход проверки списка серверов. */
data class ScanProgress(
    val checked: Int,
    val total: Int,
    /** Пригодные к применению, уже упорядоченные лучшими вперёд. */
    val best: List<NtpProbeResult>,
) {
    val finished: Boolean get() = checked >= total
}

/**
 * Проверяет весь справочник серверов и отбирает лучшие.
 *
 * Параллельность ограничена: управляющему устройству нужно также поддерживать
 * соединение с телевизором. Повторные имена проверяются один раз.
 */
class NtpScanner(
    private val probe: NtpProbe,
    private val concurrency: Int = 12,
    private val keepBest: Int = 5,
) {
    fun scan(servers: List<String>): Flow<ScanProgress> = channelFlow {
        val distinctServers = servers.distinct()
        val total = distinctServers.size
        val gate = Semaphore(concurrency)
        val usable = mutableListOf<NtpProbeResult>()
        var checked = 0
        // Отправка идёт под тем же замком, что и подсчёт. Иначе два потока,
        // посчитав 4 и 5, могут отправить их в обратном порядке, и счётчик
        // проверенных поедет назад прямо на экране
        val reporting = Mutex()

        send(ScanProgress(0, total, emptyList()))

        coroutineScope {
            distinctServers.map { server ->
                async {
                    val context = currentCoroutineContext()
                    val result = gate.withPermit { probe.test(server) { context.ensureActive() } }
                    reporting.withLock {
                        checked += 1
                        // Непригодные не копим: список нужен только чтобы
                        // предложить лучшее, а не чтобы отчитаться обо всех
                        if (result.isUsable() && result.successRate >= 80) usable += result
                        send(ScanProgress(checked, total, rankNtpServers(usable).take(keepBest)))
                    }
                }
            }.awaitAll()
        }
    }.flowOn(Dispatchers.IO)
}
