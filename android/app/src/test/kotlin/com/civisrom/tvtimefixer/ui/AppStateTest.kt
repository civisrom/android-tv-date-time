package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.diagnostics.UsbSystemState
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.device.DeviceInfo
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.R
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

    @Test fun `late device read preserves discovery and a finished NTP scan`() {
        val before = AppState(discoverySearching = true)
        val latest = before.copy(discovered = listOf(com.civisrom.tvtimefixer.adb.DiscoveredDevice(
            "TV", com.civisrom.tvtimefixer.data.DeviceAddress("192.0.2.1", 5555), com.civisrom.tvtimefixer.adb.DiscoveredDevice.Kind.LEGACY)),
            discoverySearching = false, discoveryPermissionNeeded = true,
            ntpScan = com.civisrom.tvtimefixer.data.ScanProgress(5, 5, emptyList()))
        val completed = before.copy(deviceInfo = DeviceInfo(model = "Read TV")).withLatestBackground(latest)
        assertEquals(latest.discovered, completed.discovered)
        assertFalse(completed.discoverySearching)
        assertTrue(completed.discoveryPermissionNeeded)
        assertEquals(latest.ntpScan, completed.ntpScan)
        assertEquals("Read TV", completed.deviceInfo?.model)
    }

    private val address = DeviceAddress("192.168.0.112", 5555)

    @Test fun `связь на проверке не показывает подключение по сети или USB`() {
        for (target in listOf(address, UsbDeviceAddress("/dev/bus/usb/test", "TV"))) {
            val checking = AppState(connection = ConnectionState.Checking(target))
            assertFalse(checking.connected)
            assertNull(checking.connectedAddress)
            assertNull(checking.connectedUsb)
        }
    }

    @Test fun `потеря связи убирает прежние сведения об устройстве и подтверждения времени`() {
        val usb = UsbDeviceAddress("/dev/bus/usb/test", "TV")
        val connected = AppState(connection = ConnectionState.Connected(address),
            deviceInfo = DeviceInfo(model = "TV", currentNtpServer = "pool.ntp.org"),
            ntpMessage = UiMessage(R.string.ntp_applied), ntpDiagnosticEventId = 1L,
            timeCheck = DeviceTimeCheck(DeviceTimeStatus.MATCH), timeDiagnosticEventId = 2L,
            timeZoneResult = TimeZoneUpdateResult.Applied("Europe/Moscow"), timeZoneDiagnosticEventId = 3L,
            usbDevices = listOf(usb))
        val lost = connected.connectionLost()
        assertFalse(lost.connected)
        assertNull(lost.deviceInfo)
        assertEquals("", lost.currentNtpServer)
        assertNull(lost.ntpMessage)
        assertNull(lost.ntpDiagnosticEventId)
        assertNull(lost.timeCheck)
        assertNull(lost.timeDiagnosticEventId)
        assertNull(lost.timeZoneResult)
        assertNull(lost.timeZoneDiagnosticEventId)
        assertEquals(listOf(usb), lost.usbDevices)
    }

    @Test fun `завершение команды сохраняет обнаружение и удаление USB за время чтения`() {
        val beforeRead = AppState(connection = ConnectionState.Connected(address), usbSupported = true)
        val device = UsbDeviceAddress("/dev/bus/usb/test", "TV")
        val attached = beforeRead.copy(usbDevices = listOf(device), usbAttachedCount = 1,
            usbSystemState = UsbSystemState(hostConnected = true))
        val lateResult = beforeRead.copy(deviceInfo = DeviceInfo(currentNtpServer = "time.example.org"))
        val completed = lateResult.withLatestBackground(attached)
        assertEquals(listOf(device), completed.usbDevices)
        assertEquals(1, completed.usbAttachedCount)
        assertEquals(true, completed.usbSystemState.hostConnected)
        assertEquals("time.example.org", completed.currentNtpServer)
        assertEquals(address, completed.connectedAddress)

        val detached = attached.copy(usbDevices = emptyList(), usbAttachedCount = 0,
            usbSystemState = UsbSystemState(hostConnected = false))
        val afterDetach = completed.copy(deviceInfo = DeviceInfo(currentNtpServer = "pool.ntp.org")).withLatestBackground(detached)
        assertTrue(afterDetach.usbDevices.isEmpty())
        assertEquals(0, afterDetach.usbAttachedCount)
        assertEquals(false, afterDetach.usbSystemState.hostConnected)
        assertEquals("pool.ntp.org", afterDetach.currentNtpServer)
    }

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
