package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поддельное устройство с реалистичным выводом команд. */
private class FakeDevice(
    var ntpServer: String = "time.android.com",
    private val failOnPut: Boolean = false,
    private val ignoreWrites: Boolean = false,
) : AdbClient {
    val commands = mutableListOf<String>()

    override fun shell(command: String): ShellResult {
        commands += command
        val out = when {
            command.startsWith("settings get global ntp_server") -> ntpServer.ifEmpty { "null" } + "\n"
            command.startsWith("settings put global ntp_server") -> {
                if (failOnPut) throw IllegalStateException("java.io.IOException: closed")
                if (!ignoreWrites) {
                    ntpServer = command.substringAfterLast(' ').trim('\'')
                }
                ""
            }
            command == "getprop" -> GETPROP
            command == "dumpsys battery" -> DUMPSYS_BATTERY
            command == "cat /proc/meminfo" -> MEMINFO
            command == "cat /proc/uptime" -> "98765.43 12345.67\n"
            command == "wm size" -> "Physical size: 3840x2160\n"
            command == "wm density" -> "Physical density: 320\n"
            command.contains("cpuinfo") -> "8\n"
            command == "uname -r" -> "5.10.101-android13\n"
            else -> ""
        }
        return ShellResult(out, "", 0)
    }

    override fun isAlive() = true
    override fun close() = Unit

    private companion object {
        // Значение ro.build.description намеренно содержит ']' — на этом
        // ломается наивный разбор getprop
        val GETPROP = """
            [ro.product.model]: [BRAVIA 4K GB]
            [ro.product.manufacturer]: [Sony]
            [ro.build.version.release]: [14]
            [ro.build.version.sdk]: [34]
            [ro.serialno]: [ABC123XYZ]
            [ro.product.cpu.abi]: [arm64-v8a]
            [persist.sys.timezone]: [Europe/Moscow]
            [persist.sys.locale]: [ru-RU]
            [ro.build.description]: [some[thing] odd]
            мусорная строка без скобок
        """.trimIndent()

        val DUMPSYS_BATTERY = """
            Current Battery Service state:
              AC powered: false
              level: 87
              scale: 100
              status: 2
        """.trimIndent()

        val MEMINFO = """
            MemTotal:        2035784 kB
            MemFree:          123456 kB
            MemAvailable:     987654 kB
        """.trimIndent()
    }
}

class DeviceRepositoryTest {

    @Test
    fun `смена сервера применяется и подтверждается чтением`() {
        val device = FakeDevice()
        val result = DeviceRepository(device).setNtpServer("ru.pool.ntp.org")

        assertEquals(NtpUpdateResult.Applied("ru.pool.ntp.org"), result)
        assertEquals("ru.pool.ntp.org", device.ntpServer)
        // Значение обязательно перечитывается: settings put завершается
        // успешно и тогда, когда запись не произошла
        assertTrue(device.commands.any { it.startsWith("settings get global ntp_server") })
    }

    @Test
    fun `запись без эффекта не выдаётся за успех`() {
        // Так выглядит отсутствие WRITE_SECURE_SETTINGS: команда прошла,
        // значение не изменилось
        val device = FakeDevice(ntpServer = "time.android.com", ignoreWrites = true)
        val result = DeviceRepository(device).setNtpServer("ru.pool.ntp.org")

        assertEquals(
            NtpUpdateResult.NotConfirmed("ru.pool.ntp.org", "time.android.com"),
            result,
        )
    }

    @Test
    fun `некорректный адрес не доходит до устройства`() {
        val device = FakeDevice()
        val result = DeviceRepository(device).setNtpServer("не сервер")

        assertEquals(NtpUpdateResult.InvalidServer, result)
        assertTrue("команд быть не должно", device.commands.isEmpty())
    }

    @Test
    fun `ошибка соединения не роняет приложение`() {
        val result = DeviceRepository(FakeDevice(failOnPut = true)).setNtpServer("ru.pool.ntp.org")
        assertTrue(result is NtpUpdateResult.Failed)
    }

    @Test
    fun `пустая настройка читается как пустая строка, а не как null`() {
        assertEquals("", DeviceRepository(FakeDevice(ntpServer = "")).currentNtpServer())
    }

    @Test
    fun `сведения об устройстве разбираются полностью`() {
        val info = DeviceRepository(FakeDevice()).readDeviceInfo()

        assertEquals("BRAVIA 4K GB", info.model)
        assertEquals("Sony", info.manufacturer)
        assertEquals("14", info.androidVersion)
        assertEquals("34", info.apiLevel)
        assertEquals("ABC123XYZ", info.serial)
        assertEquals("arm64-v8a", info.cpuAbi)
        assertEquals("Europe/Moscow", info.timezone)
        assertEquals("ru-RU", info.locale)
        assertEquals("87", info.batteryLevel)
        assertEquals("2035784 kB", info.totalRam)
        assertEquals("987654 kB", info.availableRam)
        assertEquals("Physical size: 3840x2160", info.screenResolution)
        assertEquals("8", info.cpuCores)
        assertEquals("5.10.101-android13", info.kernelVersion)
        assertEquals("time.android.com", info.currentNtpServer)
    }

    @Test
    fun `getprop не спотыкается о скобку внутри значения`() {
        val props = parseGetProp("[ro.build.description]: [some[thing] odd]")
        assertEquals("some[thing] odd", props["ro.build.description"])
    }

    @Test
    fun `getprop пропускает строки, которые не являются свойствами`() {
        val props = parseGetProp("мусор\n[a]: [b]\n\n[c]:[d]")
        assertEquals(mapOf("a" to "b", "c" to "d"), props)
    }

    @Test
    fun `время работы переводится в читаемый вид`() {
        assertEquals(98765L, parseUptimeSeconds("98765.43 12345.67"))
        assertEquals("1d 3h 26m", formatUptime(98765))
        assertEquals("5m", formatUptime(300))
        assertEquals("2h 0m", formatUptime(7200))
    }

    @Test
    fun `битый вывод не роняет разбор`() {
        assertEquals("", parseBatteryLevel(""))
        assertEquals("", parseMemInfo("", "MemTotal"))
        assertEquals(null, parseUptimeSeconds("мусор"))
        assertTrue(parseGetProp("").isEmpty())
    }
}
