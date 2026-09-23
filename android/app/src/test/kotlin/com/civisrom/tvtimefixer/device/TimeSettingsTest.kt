package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.terminal.splitAdbArguments
import org.junit.Assert.*
import org.junit.Test

private class SettingsDevice : AdbClient {
    val props = linkedMapOf("ro.product.model" to "Test TV", "ro.product.manufacturer" to "Maker",
        "ro.product.device" to "tv", "ro.build.version.sdk" to "36", "ro.serialno" to "real-serial",
        "persist.sys.timezone" to "UTC")
    val settings = linkedMapOf("ntp_server" to "ntp://original.example:8123|ntp://backup.example",
        "auto_time" to "1", "auto_time_zone" to "1")
    var androidId = "0123456789abcdef"
    var denied = ""
    var rejectNtpOnce = false
    var changeDuringCapture = false
    private var ntpReads = 0
    val calls = mutableListOf<String>()
    override fun isAlive() = true
    override fun close() = Unit
    override fun shell(command: String): ShellResult {
        calls += command
        fun ok(value: String = "") = ShellResult("$value\n", "", 0)
        val args = splitAdbArguments(command)
        if (command == "getprop") return ok(props.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" })
        if (command == "settings get secure android_id") return ok(androidId)
        if (args.take(2) == listOf("settings", "get")) {
            if (args.last() == "ntp_server" && ++ntpReads == 2 && changeDuringCapture) settings["ntp_server"] = "changed.example"
            return ok(settings.getValue(args.last()))
        }
        if (args.take(1) == listOf("getprop")) return ok(props.getValue(args[1]))
        if (args.take(1) == listOf("settings")) {
            val name = args[3]
            if (name == denied) return ShellResult("", "SecurityException: Permission denial", 1)
            settings[name] = if (rejectNtpOnce && name == "ntp_server") {
                rejectNtpOnce = false; "firmware-rejected.example"
            } else if (args[1] == "delete") "null" else args[4]
            return ok()
        }
        if (command == "cmd alarm help") return ShellResult("  set-timezone ZONE\n", "", 255)
        if (args.take(3) == listOf("cmd", "alarm", "set-timezone")) {
            props["persist.sys.timezone"] = args[3]; return ok()
        }
        if (command == "cmd time_zone_detector help") return ShellResult("""
            is_auto_detection_enabled
            set_auto_detection_enabled
            is_telephony_detection_supported
            is_geo_detection_supported
        """.trimIndent(), "", 255)
        if (command == "cmd time_zone_detector is_telephony_detection_supported") return ok("true")
        if (command == "cmd time_zone_detector is_geo_detection_supported") return ok("false")
        if (command == "cmd time_zone_detector is_auto_detection_enabled") return ok((settings["auto_time_zone"] != "0").toString())
        if (args.take(3) == listOf("cmd", "time_zone_detector", "set_auto_detection_enabled")) {
            settings["auto_time_zone"] = if (args[3] == "true") "1" else "0"; return ok()
        }
        error("Unexpected command: $command")
    }
    fun writes() = calls.filter { it.startsWith("settings put ") || it.startsWith("settings delete ") ||
        it.startsWith("cmd alarm set-") || it.startsWith("cmd time_zone_detector set_") }
}

class TimeSettingsTest {
    @Test fun `identity survives an OTA but rejects unknown identifiers and supports serial-less devices`() {
        val device = SettingsDevice()
        val first = timeDeviceIdentity(device.props, null)!!
        device.props["ro.build.fingerprint"] = "new-firmware"
        assertEquals(first, timeDeviceIdentity(device.props, null))
        assertFalse(first.digest.contains("real-serial"))
        device.props["ro.serialno"] = "unknown"
        assertNull(timeDeviceIdentity(device.props, "0000000000000000"))
        val fallback = timeDeviceIdentity(device.props, device.androidId)!!
        assertEquals(DeviceIdentityKind.ANDROID_ID, fallback.kind)
        assertNotEquals(fallback, timeDeviceIdentity(device.props, "fedcba9876543210"))
        assertNotEquals(first, fallback)
    }

    @Test fun `capture preserves raw settings and refuses a baseline changing between reads`() {
        val device = SettingsDevice()
        device.settings["ntp_server"] = "  ntp://existing.example:8123  "
        val read = TimeSettingsRepository(device) {}.capture()
        assertTrue(read.capturable)
        assertEquals("  ntp://existing.example:8123  ", read.settings[TimeSetting.NTP])
        assertTrue(device.writes().isEmpty())
        val changing = SettingsDevice().apply { changeDuringCapture = true }
        assertFalse(TimeSettingsRepository(changing) {}.capture().capturable)
        assertTrue(changing.writes().isEmpty())
    }

    @Test fun `restore never writes to a different device even at the same connection`() {
        val device = SettingsDevice()
        val repository = TimeSettingsRepository(device) {}
        val before = repository.capture()
        device.props["ro.serialno"] = "another-device"
        val result = repository.apply(before.identity!!, before.settings)
        assertFalse(result.identityMatches)
        assertFalse(result.confirmed)
        assertTrue(device.writes().isEmpty())
    }

    @Test fun `restore quotes raw NTP text and preserves absent flags and a literal empty setting`() {
        val device = SettingsDevice()
        val repository = TimeSettingsRepository(device) {}
        val before = repository.capture()
        val raw = "ntp://old.example'; reboot; '"
        val desired = TimeSettings(mapOf(TimeSetting.NTP to raw, TimeSetting.AUTO_TIME to "null",
            TimeSetting.AUTO_TIME_ZONE to "null", TimeSetting.TIME_ZONE to "Europe/Moscow"), effectiveAutoZone = true)
        val result = repository.apply(before.identity!!, desired)
        assertTrue(result.confirmed)
        assertEquals(raw, device.settings["ntp_server"])
        assertEquals("null", device.settings["auto_time"])
        assertEquals("null", device.settings["auto_time_zone"])
        assertEquals("Europe/Moscow", device.props["persist.sys.timezone"])
        val write = device.writes().single { it.startsWith("settings put global ntp_server ") }
        assertEquals(listOf("settings", "put", "global", "ntp_server", raw), splitAdbArguments(write))
        assertTrue(repository.apply(before.identity, TimeSettings(mapOf(TimeSetting.NTP to ""))).confirmed)
        assertEquals("", device.settings["ntp_server"])
    }

    @Test fun `multi NTP readback failure restores the exact original URI list and ports`() {
        val device = SettingsDevice()
        val original = device.settings.getValue("ntp_server")
        val repository = TimeSettingsRepository(device) {}
        val identity = repository.capture().identity!!
        device.rejectNtpOnce = true
        val result = repository.applyNtpHosts(identity, listOf("first.example", "second.example"))
        assertEquals(TimeSettingStatus.NOT_CONFIRMED, result.statuses[TimeSetting.NTP])
        assertEquals(TimeSettingStatus.VERIFIED, result.rollback?.get(TimeSetting.NTP))
        assertEquals(original, device.settings["ntp_server"])
    }

    @Test fun `older Android rejects a list before writes but still accepts a single host`() {
        val device = SettingsDevice().apply { props["ro.build.version.sdk"] = "33" }
        val repository = TimeSettingsRepository(device) {}
        val identity = repository.capture().identity!!
        val rejected = repository.applyNtpHosts(identity, listOf("a.example", "b.example"))
        assertEquals(TimeSettingStatus.UNSUPPORTED, rejected.statuses[TimeSetting.NTP])
        assertTrue(device.writes().isEmpty())
        assertTrue(repository.applyNtpHosts(identity, listOf("a.example")).confirmed)
        assertEquals("a.example", device.settings["ntp_server"])
        assertNull(ntpSettingForHosts(List(5) { "ntp$it.example" }, 36))
        assertNull(ntpSettingForHosts(listOf("A.example", "a.example"), 36))
    }

    @Test fun `snapshot rejects raw values that cannot fit the actual ADB restore command`() {
        val device = SettingsDevice()
        for (raw in listOf("x".repeat(4096), "я".repeat(2048), "'".repeat(1024))) {
            device.settings["ntp_server"] = raw
            val captured = TimeSettingsRepository(device) {}.capture()
            assertFalse(captured.capturable)
            assertTrue(device.writes().isEmpty())
        }
    }

    @Test fun `restoration accepts a safe zone captured on a newer target but never shell syntax`() {
        val device = SettingsDevice()
        val repository = TimeSettingsRepository(device) {}
        val identity = repository.capture().identity!!
        val newerZone = "Region/New_Target_Zone"
        assertFalse(isValidTimeZoneId(newerZone))
        assertTrue(repository.apply(identity, TimeSettings(mapOf(TimeSetting.TIME_ZONE to newerZone))).confirmed)
        assertEquals(newerZone, device.props["persist.sys.timezone"])
        device.calls.clear()
        val invalid = repository.apply(identity, TimeSettings(mapOf(TimeSetting.TIME_ZONE to "UTC'; reboot; '")))
        assertEquals(TimeSettingStatus.INVALID, invalid.statuses[TimeSetting.TIME_ZONE])
        assertTrue(device.writes().isEmpty())
    }

    @Test fun `manual restoration reports each failed field and continues with independent fields`() {
        val device = SettingsDevice()
        val repository = TimeSettingsRepository(device) {}
        val original = repository.capture()
        device.settings["ntp_server"] = "new.example"
        device.settings["auto_time"] = "0"
        device.denied = "auto_time"
        val result = repository.apply(original.identity!!, original.settings)
        assertFalse(result.confirmed)
        assertEquals(TimeSettingStatus.PERMISSION_DENIED, result.statuses[TimeSetting.AUTO_TIME])
        assertEquals(TimeSettingStatus.VERIFIED, result.statuses[TimeSetting.NTP])
        assertEquals(TimeSettingStatus.VERIFIED, result.statuses[TimeSetting.AUTO_TIME_ZONE])
        assertEquals(original.settings[TimeSetting.NTP], device.settings["ntp_server"])
    }
}
