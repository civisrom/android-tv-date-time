package com.civisrom.tvtimefixer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Аналог автоподбора из десктопной версии, но с меньшей параллельностью: там
 * пятьдесят потоков на компьютере, здесь телефон, которому ещё держать
 * соединение с телевизором.
 */
class NtpScanner(
    private val probe: NtpProbe,
    private val concurrency: Int = 12,
    private val keepBest: Int = 5,
) {
    fun scan(servers: List<String>): Flow<ScanProgress> = channelFlow {
        val total = servers.size
        val gate = Semaphore(concurrency)
        val usable = mutableListOf<NtpProbeResult>()
        var checked = 0
        // Отправка идёт под тем же замком, что и подсчёт. Иначе два потока,
        // посчитав 4 и 5, могут отправить их в обратном порядке, и счётчик
        // проверенных поедет назад прямо на экране
        val reporting = Mutex()

        send(ScanProgress(0, total, emptyList()))

        coroutineScope {
            servers.map { server ->
                async {
                    val result = gate.withPermit { probe.test(server) }
                    reporting.withLock {
                        checked += 1
                        // Непригодные не копим: список нужен только чтобы
                        // предложить лучшее, а не чтобы отчитаться обо всех
                        if (result.isUsable()) usable += result
                        send(ScanProgress(checked, total, rankNtpServers(usable).take(keepBest)))
                    }
                }
            }.awaitAll()
        }
    }.flowOn(Dispatchers.IO)
}
