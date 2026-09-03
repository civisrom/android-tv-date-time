package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.SntpResult
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpScannerTest {

    /** Отвечают только серверы с чётным номером, и тем быстрее, чем меньше номер. */
    private val everyOtherAnswers = object : SntpQuery {
        override fun query(host: String): SntpResult {
            val number = host.substringAfter("server").substringBefore(".").toInt()
            if (number % 2 != 0) throw SocketTimeoutException("Timeout")
            return SntpResult(rttMs = number * 10L, offsetSeconds = 0.1)
        }
    }

    private fun servers(count: Int) = (1..count).map { "server$it.example" }

    @Test
    fun `прогресс доходит до конца и считает проверенные`() = runBlocking {
        val scanner = NtpScanner(NtpProbe(everyOtherAnswers, attempts = 1), concurrency = 4)
        val updates = scanner.scan(servers(10)).toList()

        val last = updates.last()
        assertEquals(10, last.checked)
        assertEquals(10, last.total)
        assertTrue(last.finished)
    }

    @Test
    fun `счётчик проверенных не убывает`() = runBlocking {
        val scanner = NtpScanner(NtpProbe(everyOtherAnswers, attempts = 1), concurrency = 4)
        val checked = scanner.scan(servers(12)).toList().map { it.checked }

        assertEquals(checked.sorted(), checked)
    }

    @Test
    fun `в лучших только годные, упорядоченные по RTT`() = runBlocking {
        val scanner = NtpScanner(NtpProbe(everyOtherAnswers, attempts = 1), concurrency = 4)
        val best = scanner.scan(servers(10)).toList().last().best

        assertTrue("нечётные серверы не отвечают", best.all { it.isUsable() })
        assertEquals(listOf("server2.example", "server4.example"), best.take(2).map { it.server })
    }

    @Test
    fun `список лучших ограничен`() = runBlocking {
        val scanner = NtpScanner(NtpProbe(everyOtherAnswers, attempts = 1), concurrency = 4, keepBest = 3)
        val best = scanner.scan(servers(20)).toList().last().best

        assertEquals(3, best.size)
    }

    @Test
    fun `пустой список завершается сразу`() = runBlocking {
        val scanner = NtpScanner(NtpProbe(everyOtherAnswers, attempts = 1))
        val updates = scanner.scan(emptyList()).toList()

        assertEquals(1, updates.size)
        assertTrue(updates.single().finished)
    }
}
