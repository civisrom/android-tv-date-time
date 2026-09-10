package com.civisrom.tvtimefixer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответа SNTP.
 *
 * Ради этих проверок всё и затевалось: отличить «на порту 123 что-то отвечает»
 * от «отвечает сервер времени» можно только по содержимому пакета. Иначе
 * пользователь пропишет телевизору адрес, который выглядит рабочим, а часы по
 * нему не пойдут — и узнает он об этом очень нескоро.
 */
class SntpPacketTest {

    @Test fun `timestamps before after and across the 2036 era boundary stay in this century`() {
        val boundary = (0x100000000L - 2_208_988_800L) * 1000L
        for (now in listOf(boundary - 10_000, boundary + 10_000, boundary - 20)) {
            val result = SntpPacket.parse(serverReply(receiveMs = now + 10, transmitMs = now + 30), now, now + 50)!!
            assertTrue(kotlin.math.abs(result.referenceTimeMillis!! - now - 45) <= 2)
            assertTrue(kotlin.math.abs(result.offsetSeconds) < 1)
        }
    }

    @Test fun `network reference remains correct when local wall clock is far behind`() {
        val local = 946_684_800_000L
        val network = 1_800_000_000_000L
        val result = SntpPacket.parse(serverReply(receiveMs = network + 10, transmitMs = network + 20),
            local, local + 40)!!
        assertTrue(kotlin.math.abs(network + 35 - result.referenceTimeMillis!!) <= 2)
    }

    @Test fun `unsynchronized servers and unsupported NTP versions are rejected`() {
        val now = 1_800_000_000_000L
        for (header in listOf(0xDC, 0x14, 0x2C)) {
            val reply = serverReply(receiveMs = now, transmitMs = now).also { it[0] = header.toByte() }
            assertNull(SntpPacket.parse(reply, now, now + 10))
        }
    }

    @Test fun `reply must echo this request timestamp`() {
        val now = 1_800_000_000_123L
        val request = SntpPacket.request(now)
        val reply = serverReply(receiveMs = now + 10, transmitMs = now + 11)
        assertNull(SntpPacket.parse(reply, now, now + 20, request))
        request.copyInto(reply, 24, 40, 48)
        assertNotNull(SntpPacket.parse(reply, now, now + 20, request))
        reply[24] = (reply[24].toInt() xor 1).toByte()
        assertNull(SntpPacket.parse(reply, now, now + 20, request))
    }

    @Test fun `inconsistent timestamp intervals are rejected`() {
        val now = 1_800_000_000_000L
        assertNull(SntpPacket.parse(serverReply(receiveMs = now + 20, transmitMs = now), now, now + 30))
        assertNull(SntpPacket.parse(serverReply(receiveMs = now, transmitMs = now + 100), now, now + 20))
        assertNull(SntpPacket.parse(serverReply(receiveMs = now, transmitMs = now), now + 1, now))
    }

    /** Собирает правдоподобный ответ сервера. */
    private fun serverReply(
        mode: Int = 4,
        stratum: Int = 2,
        receiveMs: Long,
        transmitMs: Long,
    ): ByteArray {
        val packet = ByteArray(SntpPacket.SIZE)
        packet[0] = ((3 shl 3) or mode).toByte()
        packet[1] = stratum.toByte()
        writeTimestamp(packet, 32, receiveMs)
        writeTimestamp(packet, 40, transmitMs)
        return packet
    }

    private fun writeTimestamp(buffer: ByteArray, offset: Int, unixMs: Long) {
        if (unixMs == 0L) return
        val seconds = unixMs / 1000L + 2_208_988_800L
        val fraction = (unixMs % 1000L) * 0x100000000L / 1000L
        for (i in 0 until 4) {
            buffer[offset + i] = ((seconds shr (24 - 8 * i)) and 0xFF).toByte()
        }
        for (i in 0 until 4) {
            buffer[offset + 4 + i] = ((fraction shr (24 - 8 * i)) and 0xFF).toByte()
        }
    }

    @Test
    fun `запрос имеет верную длину и заголовок`() {
        val request = SntpPacket.request()
        assertEquals(48, request.size)
        // LI = 0, VN = 3, Mode = 3 (клиент)
        assertEquals(0x1B, request[0].toInt() and 0xFF)
        assertTrue("остальные байты запроса обязаны быть нулями", request.drop(1).all { it == 0.toByte() })
    }

    @Test
    fun `ответ сервера разбирается, смещение и задержка считаются`() {
        // Часы устройства отстают ровно на 5 секунд, обмен занял 40 мс
        val t1 = 1_800_000_000_000L
        val t4 = t1 + 40
        val serverTime = t1 + 5_000

        val result = SntpPacket.parse(
            serverReply(receiveMs = serverTime + 10, transmitMs = serverTime + 20),
            t1,
            t4,
        )

        assertNotNull(result)
        assertEquals(5.0, result!!.offsetSeconds, 0.05)
        assertTrue("задержка должна быть неотрицательной", result.rttMs >= 0)
        assertTrue("задержка не может превышать полное время обмена", result.rttMs <= 40)
    }

    @Test
    fun `короткий пакет отвергается`() {
        assertNull(SntpPacket.parse(ByteArray(20), 0, 10))
        assertNull(SntpPacket.parse(ByteArray(0), 0, 10))
    }

    @Test
    fun `не ответ сервера отвергается`() {
        val now = 1_800_000_000_000L
        // Режим 3 — это запрос клиента, а не ответ: так выглядит служба,
        // которая просто отражает присланные байты
        assertNull(
            SntpPacket.parse(
                serverReply(mode = 3, receiveMs = now, transmitMs = now),
                now,
                now + 10,
            ),
        )
    }

    @Test
    fun `Kiss-o-Death и невозможный stratum отвергаются`() {
        val now = 1_800_000_000_000L
        // Ноль означает, что сервер отвечает, но обслуживать отказывается
        assertNull(
            SntpPacket.parse(serverReply(stratum = 0, receiveMs = now, transmitMs = now), now, now + 10),
        )
        assertNull(
            SntpPacket.parse(serverReply(stratum = 16, receiveMs = now, transmitMs = now), now, now + 10),
        )
        assertNull(
            SntpPacket.parse(serverReply(stratum = 200, receiveMs = now, transmitMs = now), now, now + 10),
        )
    }

    @Test
    fun `нулевые метки времени отвергаются`() {
        val now = 1_800_000_000_000L
        // Пакет верной длины и режима, но времени в нём нет — принимать нельзя.
        // Обе метки участвуют в расчёте смещения, поэтому проверяются обе
        assertNull(
            SntpPacket.parse(serverReply(receiveMs = now, transmitMs = 0), now, now + 10),
        )
        assertNull(
            SntpPacket.parse(serverReply(receiveMs = 0, transmitMs = now), now, now + 10),
        )
    }
}
