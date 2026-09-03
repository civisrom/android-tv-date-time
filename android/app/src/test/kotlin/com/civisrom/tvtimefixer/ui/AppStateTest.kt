package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.data.DeviceAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Состояние подключения в том виде, в каком его читает экран.
 *
 * Найдено на живом устройстве: после отмены запроса на экране телевизора
 * найденное устройство подписывалось «Подключено» и теряло кнопку — повторить
 * попытку было нечем. Причина была в том, что экран сравнивал адрес устройства
 * с `addressOrNull()`, а та отдаёт адрес и при отказе тоже.
 */
class AppStateTest {

    private val address = DeviceAddress("192.168.0.112", 5555)

    @Test
    fun `подключение считается установленным только в состоянии Connected`() {
        assertEquals(
            address,
            AppState(connection = ConnectionState.Connected(address)).connectedAddress,
        )
        assertTrue(AppState(connection = ConnectionState.Connected(address)).connected)
    }

    @Test
    fun `отказ не выдаёт себя за установленную связь`() {
        // Отмена запроса на телевизоре приводит сюда, и адрес в состоянии есть
        val failed = AppState(
            connection = ConnectionState.Failed(address, ConnectionError.UNREACHABLE),
        )

        assertNull("иначе строка устройства подпишется «Подключено»", failed.connectedAddress)
        assertFalse(failed.connected)
    }

    @Test
    fun `отказ авторизации тоже не считается связью`() {
        val declined = AppState(
            connection = ConnectionState.Failed(address, ConnectionError.NOT_AUTHORIZED),
        )
        assertNull(declined.connectedAddress)
    }

    @Test
    fun `идущее подключение ещё не связь`() {
        val connecting = AppState(connection = ConnectionState.Connecting(address))
        assertNull(connecting.connectedAddress)
        assertFalse(connecting.connected)
    }

    @Test
    fun `без подключения адреса нет`() {
        assertNull(AppState().connectedAddress)
        assertFalse(AppState().connected)
    }

    @Test
    fun `адрес другого устройства не совпадает с подключённым`() {
        val state = AppState(connection = ConnectionState.Connected(address))
        val other = DeviceAddress("192.168.0.113", 5555)

        assertEquals(address, state.connectedAddress)
        assertFalse("совпадение по адресу должно быть точным", other == state.connectedAddress)
    }
}
