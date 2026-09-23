package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.terminal.splitAdbArguments
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

private class SafetySettingsDevice : AdbClient {
    var serial = "original-serial"
    val values = mutableMapOf("ntp_server" to "original.example", "auto_time" to "1", "auto_time_zone" to "1")
    var before: (String) -> Unit = {}
    var after: (String) -> Unit = {}
    var deniedRead: String? = null
    val writes = mutableListOf<String>()
    override fun isAlive() = true
    override fun close() = Unit
    override fun shell(command: String): ShellResult {
        before(command)
        if (command == deniedRead) return ShellResult("Permission Denial", "", 0)
        val args = splitAdbArguments(command)
        val output = when {
            command == "getprop" -> """
                [ro.serialno]: [$serial]
                [ro.product.model]: [Test TV]
                [ro.product.manufacturer]: [Maker]
                [ro.product.device]: [test-tv]
                [ro.build.version.sdk]: [29]
            """.trimIndent()
            command == "getprop persist.sys.timezone" -> "UTC"
            args.take(3) == listOf("settings", "get", "global") -> values.getValue(args[3])
            args.take(3) == listOf("settings", "put", "global") -> {
                writes += command
                values[args[3]] = args[4]
                ""
            }
            args.take(3) == listOf("settings", "delete", "global") -> {
                writes += command
                values[args[3]] = "null"
                ""
            }
            else -> error("Unexpected test command: $command")
        }
        after(command)
        return ShellResult("$output\n", "", 0)
    }
}

class TimeSettingsSafetyTest {
    @Test fun `identity swap after first field prevents later writes and rollback on the new device`() {
        val device = SafetySettingsDevice()
        val repository = TimeSettingsRepository(device) {}
        val original = repository.capture()
        device.after = { command ->
            if (command.startsWith("settings put global ntp_server ")) device.serial = "replacement-serial"
        }
        val desired = TimeSettings(original.settings.values + mapOf(TimeSetting.NTP to "new.example", TimeSetting.AUTO_TIME to "0"))
        val changed = repository.apply(original.identity!!, desired, rollbackOnFailure = true)
        assertFalse(changed.identityMatches)
        assertFalse(changed.confirmed)
        assertNull(changed.rollback)
        assertEquals(1, device.writes.size)
        assertEquals("1", device.values["auto_time"])
    }

    @Test fun `permission denied with exit zero cannot become a captured setting`() {
        val device = SafetySettingsDevice().apply { deniedRead = "settings get global ntp_server" }
        val read = TimeSettingsRepository(device) {}.capture()
        assertFalse(read.capturable)
        assertEquals(TimeSettingStatus.PERMISSION_DENIED, read.statuses[TimeSetting.NTP])
        assertNull(read.settings[TimeSetting.NTP])
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `identity change between complete reads makes the snapshot inconsistent`() {
        val device = SafetySettingsDevice()
        var propertyReads = 0
        device.before = { command ->
            if (command == "getprop" && ++propertyReads == 2) device.serial = "replacement-serial"
        }
        val read = TimeSettingsRepository(device) {}.capture()
        assertTrue(read.settings.complete)
        assertFalse(read.consistent)
        assertFalse(read.capturable)
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `cancellation during capture propagates without converting it to an ordinary failure`() {
        val device = SafetySettingsDevice()
        device.before = { command ->
            if (command == "settings get global auto_time") throw CancellationException("test cancellation")
        }
        assertThrows(CancellationException::class.java) { TimeSettingsRepository(device) {}.capture() }
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `empty absent and custom port baselines remain exact after independent captures and restores`() {
        for (raw in listOf("", "null", "  ", "ntp://[2001:db8::5]:8123|ntp://backup.example:123")) {
            val device = SafetySettingsDevice().apply { values["ntp_server"] = raw }
            val repository = TimeSettingsRepository(device) {}
            val baseline = repository.capture()
            assertTrue(raw, baseline.capturable)
            assertEquals(raw, baseline.settings[TimeSetting.NTP])
            device.values["ntp_server"] = "changed.example"
            assertTrue(raw, repository.apply(baseline.identity!!, baseline.settings).confirmed)
            assertEquals(raw, device.values["ntp_server"])
            assertEquals(raw == "null", device.writes.any { it == "settings delete global ntp_server" })
        }
    }
}
