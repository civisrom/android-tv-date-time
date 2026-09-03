package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.NotAnNtpServerException
import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.SntpResult
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поддельный сервер времени: отвечает по сценарию, заданному в тесте. */
private class FakeSntp(private val answers: Map<String, List<Any>>) : SntpQuery {
    private val calls = mutableMapOf<String, Int>()

    override fun query(host: String): SntpResult {
        val script = answers[host] ?: throw SocketTimeoutException("Timeout")
        val index = calls.getOrDefault(host, 0)
        calls[host] = index + 1
        return when (val answer = script[index % script.size]) {
            is SntpResult -> answer
            is Throwable -> throw answer
            else -> error("непонятный сценарий")
        }
    }
}

class NtpProbeTest {

    @Test
    fun `отвечающий сервер даёт средний RTT и полную долю успехов`() {
        val probe = NtpProbe(
            FakeSntp(mapOf("time.google.com" to listOf(SntpResult(30, 0.4), SntpResult(50, 0.6)))),
            attempts = 2,
        )
        val result = probe.test("time.google.com")

        assertTrue(result.reachable)
        assertEquals(100, result.successRate)
        assertEquals(40L, result.avgRttMs)
        assertEquals(0.5, result.offsetSeconds!!, 0.001)
        assertNull(result.error)
        assertTrue(result.isUsable())
    }

    @Test
    fun `частичный отказ снижает долю успехов, но сервер остаётся годным`() {
        val probe = NtpProbe(
            FakeSntp(mapOf("ntp.example" to listOf(SntpResult(20, 0.1), SocketTimeoutException("Timeout")))),
            attempts = 2,
        )
        val result = probe.test("ntp.example")

        assertTrue(result.reachable)
        assertEquals(50, result.successRate)
        assertEquals(20L, result.avgRttMs)
        assertTrue(result.isUsable())
    }

    @Test
    fun `молчащий сервер недоступен и негоден`() {
        val result = NtpProbe(FakeSntp(emptyMap()), attempts = 2).test("nowhere.example")

        assertFalse(result.reachable)
        assertEquals(0, result.successRate)
        assertNull(result.avgRttMs)
        assertFalse(result.isUsable())
        assertTrue(result.error!!.isNotBlank())
    }

    @Test
    fun `отвечающий не по NTP адрес негоден`() {
        // Ровно тот случай, ради которого проба и нужна: адрес существует и
        // что-то отвечает, но сервером времени не является
        val probe = NtpProbe(
            FakeSntp(mapOf("example.com" to listOf(NotAnNtpServerException("не по протоколу NTP")))),
            attempts = 2,
        )
        val result = probe.test("example.com")

        assertFalse(result.reachable)
        assertFalse(result.isUsable())
        assertTrue(result.error!!.contains("NTP"))
    }

    @Test
    fun `сервер с уехавшими часами отвергается`() {
        // Отвечает исправно, но сообщает время на два часа вперёд: задать такой
        // телевизору — значит сломать часы, а не починить
        val probe = NtpProbe(
            FakeSntp(mapOf("skewed.example" to listOf(SntpResult(15, 7200.0)))),
            attempts = 1,
        )
        val result = probe.test("skewed.example")

        assertTrue("сервер отвечает", result.reachable)
        assertFalse("но применять его нельзя", result.isUsable())
    }

    @Test
    fun `граница смещения совпадает с десктопной версией`() {
        val ok = NtpProbeResult("a", true, 100, 10, MAX_OFFSET_SECONDS, null)
        val tooMuch = NtpProbeResult("b", true, 100, 10, MAX_OFFSET_SECONDS + 0.1, null)
        assertTrue(ok.isUsable())
        assertFalse(tooMuch.isUsable())
        assertEquals(60.0, MAX_OFFSET_SECONDS, 0.0)
    }

    @Test
    fun `неверный адрес отбраковывается без обращения к сети`() {
        val probe = NtpProbe(
            object : SntpQuery {
                override fun query(host: String) = error("сети быть не должно")
            },
            attempts = 2,
        )
        val result = probe.test("не адрес")

        assertFalse(result.reachable)
        assertFalse(result.isUsable())
    }

    @Test
    fun `IP-адрес принимается как сервер времени`() {
        val probe = NtpProbe(
            FakeSntp(mapOf("216.239.35.0" to listOf(SntpResult(25, 0.2)))),
            attempts = 1,
        )
        assertTrue(probe.test("216.239.35.0").isUsable())
    }

    @Test
    fun `порядок совпадает с десктопным - успехи по убыванию, RTT по возрастанию`() {
        val slowButReliable = NtpProbeResult("slow", true, 100, 200, 0.1, null)
        val fastButFlaky = NtpProbeResult("flaky", true, 50, 10, 0.1, null)
        val fastAndReliable = NtpProbeResult("fast", true, 100, 20, 0.1, null)
        val dead = NtpProbeResult("dead", false, 0, null, null, "Timeout")

        val ranked = rankNtpServers(listOf(dead, slowButReliable, fastButFlaky, fastAndReliable))

        assertEquals(listOf("fast", "slow", "flaky", "dead"), ranked.map { it.server })
    }
}
