package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            command == "settings delete global ntp_server" -> {
                if (!ignoreWrites) ntpServer = ""
                "Deleted 1 rows"
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
    @Test fun `ADB permission denial is distinct from a connection failure for set reset and undo`() {
        val denials = listOf(
            ShellResult("", "java.lang.SecurityException: Permission denial: writing settings requires android.permission.WRITE_SECURE_SETTINGS", 255),
            ShellResult("Permission denied", "", 1),
            ShellResult("java.lang.SecurityException: Permission denial", "", 0),
        )
        val changes = listOf<(DeviceRepository) -> NtpUpdateResult>(
            { it.setNtpServer("pool.ntp.org") },
            { it.resetNtpServer() },
            { it.undoNtpServer(NtpUpdateResult.Applied("time.android.com", previous = "previous.example")) },
        )
        for (denial in denials) for (change in changes) {
            val device = FakeDevice()
            val writes = mutableListOf<String>()
            val client = object : AdbClient by device {
                override fun shell(command: String): ShellResult {
                    if (command.startsWith("settings put ") || command.startsWith("settings delete ")) {
                        writes += command
                        return denial
                    }
                    return device.shell(command)
                }
            }
            val result = change(DeviceRepository(client))
            assertEquals(NtpUpdateResult.PermissionDenied, result)
            assertEquals(1, writes.size)
            assertEquals("time.android.com", device.ntpServer)
            assertTrue(client.isAlive())
        }
    }

    @Test fun `terminal identification reads only target properties without running full diagnostics`() {
        val client = FakeDevice()
        assertEquals("Sony BRAVIA 4K GB", DeviceRepository(client).readDeviceName())
        assertEquals(listOf("getprop"), client.commands)
    }

    @Test fun `device display name combines manufacturer and model without duplicating the brand`() {
        assertEquals("NVIDIA SHIELD Android TV", DeviceInfo(manufacturer = " NVIDIA ", model = "SHIELD Android TV").displayName)
        assertEquals("NVIDIA SHIELD", DeviceInfo(manufacturer = "nvidia", model = "NVIDIA SHIELD").displayName)
        assertEquals("SHIELD", DeviceInfo(model = "SHIELD").displayName)
        assertEquals("NVIDIA", DeviceInfo(manufacturer = "NVIDIA").displayName)
        assertEquals("", DeviceInfo().displayName)
        assertEquals("", DeviceInfo(manufacturer = "unknown", model = "null").displayName)
    }

    @Test fun `missing or rejected model metadata does not turn a confirmed connection into a failure`() {
        listOf(ShellResult("unavailable", "", 1), ShellResult("[other]: [property]", "", 0)).forEach { result ->
            val client = object : AdbClient {
                override fun shell(command: String) = result
                override fun isAlive() = true
                override fun close() = error("Metadata lookup must not close the connection")
            }
            assertEquals("", DeviceRepository(client).readDeviceName())
        }
    }

    @Test fun `system reset and undo preserve a modern multi-server setting`() {
        val raw = "ntp://time.example.org:1123|ntp://other.example.org"
        val device = FakeDevice(ntpServer = raw)
        val repository = DeviceRepository(device)
        val reset = repository.resetNtpServer() as NtpUpdateResult.Applied
        assertEquals("null", repository.currentNtpServer())
        assertEquals(raw, reset.previous)
        val undo = repository.undoNtpServer(reset) as NtpUpdateResult.Applied
        assertEquals(raw, undo.server)
        assertEquals(raw, repository.currentNtpServer())
        device.ntpServer = "external.example.org"
        val count = device.commands.count { it.startsWith("settings put") || it.startsWith("settings delete") }
        assertTrue(repository.undoNtpServer(undo) is NtpUpdateResult.NotConfirmed)
        assertEquals(count, device.commands.count { it.startsWith("settings put") || it.startsWith("settings delete") })
    }
    @Test fun `Android 6 storage falls back when df rejects the k option`() {
        val target = object : AdbClient {
            override fun isAlive() = true
            override fun close() = Unit
            override fun shell(command: String) = when (command) {
                "getprop" -> ShellResult("[ro.product.model]: [Old TV]\n[ro.build.version.sdk]: [23]", "", 0)
                "df -k /data" -> ShellResult("", "Could not stat -k", 1)
                "df /data" -> ShellResult("Filesystem Size Used Free Blksize\n/data 8.0G 6.0G 2.0G 4096", "", 0)
                else -> ShellResult("", "", 0)
            }
        }
        val info = DeviceRepository(target).readDeviceInfo()
        assertEquals("8.00 GiB", info.storageTotal)
        assertEquals("2.00 GiB", info.storageAvailable)
    }

    @Test fun `automatic zone reflects detection support rather than a stale TV setting`() {
        fun read(api: Int, telephony: String, geo: String, enabled: String): Boolean? {
            val client = object : AdbClient {
                override fun isAlive() = true
                override fun close() = Unit
                override fun shell(command: String) = ShellResult(when (command) {
                    "cmd time_zone_detector is_telephony_detection_supported" -> telephony
                    "cmd time_zone_detector is_geo_detection_supported" -> geo
                    "cmd time_zone_detector is_auto_detection_enabled" -> enabled
                    "settings get global auto_time_zone" -> "1"
                    else -> error(command)
                }, "", 0)
            }
            return DeviceRepository(client).automaticTimeZoneEnabled(api)
        }
        assertEquals(false, read(31, "false", "false", "true"))
        assertEquals(true, read(31, "true", "false", "true"))
        assertEquals(false, read(36, "false", "true", "false"))
        assertNull(read(36, "false", "unknown command", "true"))
        assertNull(read(36, "true", "true", "unknown command"))
        assertEquals(true, read(30, "", "", ""))
    }

    @Test fun `new properties come from the connected target and all reads are optional`() {
        val target = object : AdbClient {
            override fun isAlive() = true
            override fun close() = Unit
            override fun shell(command: String) = when (command) {
                "getprop" -> ShellResult("""
                    [ro.product.model]: [Target TV]
                    [ro.soc.model]: [Target chip]
                    [ro.soc.manufacturer]: [Target vendor]
                    [ro.build.display.id]: [TV 11 build]
                    [ro.build.version.security_patch]: [2026-08-01]
                    [ro.product.cpu.abi]: [armeabi-v7a]
                    [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a]
                    [ro.bootloader]: [unknown]
                """.trimIndent(), "", 0)
                "settings get global auto_time" -> ShellResult("1", "", 0)
                "settings get global auto_time_zone" -> ShellResult("null", "", 0)
                else -> ShellResult("permission denied", "permission denied", 1)
            }
        }
        val info = DeviceRepository(target).readDeviceInfo()
        assertEquals("Target TV", info.model)
        assertEquals("Target chip", info.socModel)
        assertEquals("Target vendor", info.socManufacturer)
        assertEquals("TV 11 build", info.buildDisplay)
        assertEquals("2026-08-01", info.securityPatch)
        assertEquals("arm64-v8a,armeabi-v7a", info.cpuAbi)
        assertEquals("", info.bootloader)
        assertEquals("", info.screenResolution)
        assertEquals("", info.kernelVersion)
        assertEquals(true, info.automaticTime)
        assertEquals(null, info.automaticTimeZone)
    }

    @Test(expected = IllegalStateException::class)
    fun `getprop failure exit cannot become successful empty device information`() {
        val target = object : AdbClient {
            override fun isAlive() = true
            override fun close() = Unit
            override fun shell(command: String) = ShellResult("Permission Denial", "", 1)
        }
        DeviceRepository(target).readDeviceInfo()
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun `cancellation stops further optional reads`() {
        val target = object : AdbClient {
            override fun isAlive() = true
            override fun close() = Unit
            override fun shell(command: String): ShellResult {
                if (command == "getprop") return ShellResult("[ro.product.model]: [TV]", "", 0)
                throw java.util.concurrent.CancellationException()
            }
        }
        DeviceRepository(target).readDeviceInfo()
    }

    @Test fun `diagnostic callback failure cannot replace the NTP result`() {
        val repository = DeviceRepository(FakeDevice(failOnPut = true)) {
            throw java.io.IOException("storage unavailable")
        }
        assertTrue(repository.setNtpServer("pool.ntp.org") is NtpUpdateResult.Failed)
    }


    @Test
    fun `смена сервера применяется и подтверждается чтением`() {
        val device = FakeDevice()
        val result = DeviceRepository(device).setNtpServer("ru.pool.ntp.org")

        assertEquals(NtpUpdateResult.Applied("ru.pool.ntp.org", previous = "time.android.com"), result)
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
        for (server in listOf("не сервер", "time.-pool.org", "time.pool-.org", "time.google.com:123",
            "https://time.google.com", "999.0.0.1", "192.168.1.1:123")) {
            assertEquals(server, NtpUpdateResult.InvalidServer, DeviceRepository(device).setNtpServer(server))
        }
        assertTrue("команд быть не должно", device.commands.isEmpty())
    }

    @Test
    fun `ошибка соединения не роняет приложение`() {
        val result = DeviceRepository(FakeDevice(failOnPut = true)).setNtpServer("ru.pool.ntp.org")
        assertTrue(result is NtpUpdateResult.Failed)
    }

    @Test
    fun `отсутствующая настройка означает системный источник`() {
        assertEquals("null", DeviceRepository(FakeDevice(ntpServer = "")).currentNtpServer())
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

    /**
     * Устройство, у которого часть команд отвечает отказом.
     *
     * Ровно это и случилось на живом телевизоре: раздел «Устройство» оказался
     * пустым, а кнопка «Обновить» выглядела ненажатой, потому что одна
     * упавшая команда уносила весь результат.
     */
    private class PartlyBrokenDevice(private val failing: Set<String>) : AdbClient {
        override fun shell(command: String): ShellResult {
            if (failing.any { command.startsWith(it) }) {
                throw IllegalStateException("java.io.IOException: closed")
            }
            val out = when {
                command == "getprop" -> "[ro.product.model]: [BRAVIA 4K GB]\n" +
                    "[ro.product.manufacturer]: [Sony]\n"
                command == "uname -r" -> "5.10.101-android13\n"
                else -> ""
            }
            return ShellResult(out, "", 0)
        }

        override fun isAlive() = true
        override fun close() = Unit
    }

    @Test
    fun `отказ необязательной команды не стирает остальные сведения`() {
        val device = PartlyBrokenDevice(
            failing = setOf("dumpsys battery", "wm ", "cat /proc/meminfo", "settings get"),
        )
        val info = DeviceRepository(device).readDeviceInfo()

        // Главное: результат вообще есть, а не потерян целиком
        assertEquals("BRAVIA 4K GB", info.model)
        assertEquals("Sony", info.manufacturer)
        assertEquals("5.10.101-android13", info.kernelVersion)
        // Упавшие команды дают пустоту, и такие строки экран просто не рисует
        assertEquals("", info.batteryLevel)
        assertEquals("", info.totalRam)
        assertEquals("", info.screenResolution)
        assertEquals("", info.currentNtpServer)
    }

    @Test
    fun `отказ getprop пробрасывается наружу`() {
        // getprop заодно проверяет связь: если не отвечает он, молчать нельзя —
        // вызывающий обязан показать причину
        val device = PartlyBrokenDevice(failing = setOf("getprop"))
        try {
            DeviceRepository(device).readDeviceInfo()
            fail("ожидалось исключение")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }
}
