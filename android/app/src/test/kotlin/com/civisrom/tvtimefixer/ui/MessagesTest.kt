package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun `у каждой причины отказа есть своя строка`() {
        val used = ConnectionError.entries.map { it.messageRes() }
        assertTrue("нулевой идентификатор строки", used.none { it == 0 })
        // Разные причины требуют разных действий от человека, поэтому и
        // сообщения обязаны быть разными
        assertEquals(ConnectionError.entries.size, used.distinct().size)
    }

    @Test
    fun `у каждого вида найденного устройства есть подпись`() {
        val used = DiscoveredDevice.Kind.entries.map { it.labelRes() }
        assertTrue(used.none { it == 0 })
        assertEquals(DiscoveredDevice.Kind.entries.size, used.distinct().size)
    }

    @Test
    fun `результаты смены сервера дают разные сообщения с нужными подстановками`() {
        val applied = NtpUpdateResult.Applied("ru.pool.ntp.org").toUiMessage()
        assertEquals(listOf("ru.pool.ntp.org"), applied.args)

        val notConfirmed = NtpUpdateResult.NotConfirmed("ru.pool.ntp.org", "time.android.com").toUiMessage()
        // Показываем то, что устройство сообщает сейчас, а не то, что мы хотели
        assertEquals(listOf("time.android.com"), notConfirmed.args)
        assertNotEquals(applied.res, notConfirmed.res)

        val invalid = NtpUpdateResult.InvalidServer.toUiMessage()
        assertTrue(invalid.args.isEmpty())

        val failed = NtpUpdateResult.Failed("timeout").toUiMessage()
        assertEquals(listOf("timeout"), failed.args)
    }

    @Test
    fun `пустое значение в NotConfirmed не превращается в пустоту на экране`() {
        val message = NtpUpdateResult.NotConfirmed("ru.pool.ntp.org", "").toUiMessage()
        assertEquals(listOf("—"), message.args)
    }
}
