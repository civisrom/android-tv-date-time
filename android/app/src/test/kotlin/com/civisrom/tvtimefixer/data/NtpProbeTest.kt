package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.net.NotAnNtpServerException
import com.civisrom.tvtimefixer.net.SntpQuery
import com.civisrom.tvtimefixer.net.SntpResult
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
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

    @Test fun `five spaced samples retain median jitter and losses without an offset limit`() {
        var pauses = 0
        val replies = listOf(SntpResult(10, 31_536_000.0), SntpResult(20, 31_536_000.0),
            SntpResult(30, 31_536_000.0), SntpResult(100, 31_536_000.0), SocketTimeoutException())
        val result = NtpProbe(FakeSntp(mapOf("time.example" to replies)), attempts = 5,
            pause = { pauses++ }).test("time.example")
        assertEquals(4, pauses)
        assertEquals(80, result.successRate)
        assertEquals(25L, result.medianRttMs)
        assertTrue(result.rttJitterMs!! > 30)
        assertTrue(result.isUsable())
    }

    @Test fun `ranking prefers stable delay over an occasional fast sample`() {
        val steady = NtpProbeResult("steady.example", true, 100, 25, 0.0, null,
            medianRttMs = 25, rttJitterMs = 2.0)
        val spiky = steady.copy(server = "spiky.example", medianRttMs = 10, rttJitterMs = 100.0)
        val lossy = steady.copy(server = "lossy.example", successRate = 80, medianRttMs = 1, rttJitterMs = 0.0)
        assertEquals(listOf(steady, spiky, lossy), rankNtpServers(listOf(lossy, spiky, steady)))
    }

    @Test fun `cancelled probe stops before the next network request`() {
        var calls = 0
        val query = object : SntpQuery {
            override fun query(host: String): SntpResult { calls++; return SntpResult(10, 0.0) }
        }
        try {
            NtpProbe(query, attempts = 5).test("time.example") {
                if (calls > 0) throw kotlinx.coroutines.CancellationException()
            }
            org.junit.Assert.fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, calls)
    }

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
    fun `сбитые локальные часы не мешают выбрать отвечающий NTP-сервер`() {
        for (offset in listOf(-31_536_000.0, -7200.0, 7200.0, 31_536_000.0)) {
            val probe = NtpProbe(
                FakeSntp(mapOf("time.example" to listOf(SntpResult(15, offset)))), attempts = 1)
            val result = probe.test("time.example")
            assertTrue(result.reachable)
            assertTrue(result.isUsable())
            assertEquals(offset, result.offsetSeconds!!, 0.0)
        }
    }

    @Test
    fun `отсутствие ответа или некорректные числовые данные не проходят проверку`() {
        val valid = NtpProbeResult("time.example", true, 100, 10, 0.0, null)
        assertFalse(valid.copy(reachable = false).isUsable())
        for (offset in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(valid.copy(offsetSeconds = offset).isUsable())
        }
    }

    @Test
    fun `неверный адрес отбраковывается без обращения к сети`() {
        val probe = NtpProbe(
            object : SntpQuery {
                override fun query(host: String) = error("сети быть не должно")
            },
            attempts = 2,
        )
        for (server in listOf("не адрес", "time.-pool.org", "time.pool-.org", "time.google.com:123",
            "https://time.google.com", "999.0.0.1", "192.168.1.1:123")) {
            val result = probe.test(server)
            assertFalse(result.reachable)
            assertFalse(result.isUsable())
            assertEquals(NtpProbeFailure.INVALID_ADDRESS, result.failure)
        }
    }

    @Test
    fun `DNS таймаут неверный ответ и сетевая ошибка имеют разные причины отказа`() {
        for ((error, expected) in listOf(
            UnknownHostException("dns fixture") to NtpProbeFailure.DNS,
            SocketTimeoutException("timeout fixture") to NtpProbeFailure.TIMEOUT,
            NotAnNtpServerException("invalid reply fixture") to NtpProbeFailure.INVALID_RESPONSE,
            IOException("network fixture") to NtpProbeFailure.NETWORK,
        )) {
            val probe = NtpProbe(FakeSntp(mapOf("time.example" to listOf(error))), attempts = 1)
            val result = probe.test("time.example")
            assertFalse(result.isUsable())
            assertEquals(expected, result.failure)
            assertEquals(error.message, result.error)
        }
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
    fun `IP отвечавшего сервера доходит до результата`() {
        // Адрес нужен экрану: часть прошивок не резолвит имена, и тогда сервер
        // задают числом. Отдельного запроса к DNS ради этого не делается —
        // имя всё равно разрешается перед отправкой пакета
        val probe = NtpProbe(
            FakeSntp(mapOf("time.google.com" to listOf(SntpResult(30, 0.1, "216.239.35.0")))),
            attempts = 1,
        )
        assertEquals("216.239.35.0", probe.test("time.google.com").ipAddress)
    }

    @Test
    fun `у недоступного сервера IP не выдумывается`() {
        val result = NtpProbe(FakeSntp(emptyMap()), attempts = 1).test("nowhere.example")
        assertNull(result.ipAddress)
    }

    @Test
    fun `IP берётся от первой удавшейся попытки`() {
        val probe = NtpProbe(
            FakeSntp(mapOf("ntp.example" to listOf(
                SocketTimeoutException("Timeout"),
                SntpResult(20, 0.1, "10.0.0.7"),
            ))),
            attempts = 2,
        )
        assertEquals("10.0.0.7", probe.test("ntp.example").ipAddress)
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
