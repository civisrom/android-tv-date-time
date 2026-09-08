package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

private class ZoneDevice : AdbClient {
    var sdk = 36
    var zone = "UTC"
    var auto = "true"
    var alarmAvailable = true
    var modernAvailable = true
    var telephony = true
    var geo = false
    var ignoreZone = false
    var ignoreAuto = false
    var rejectedCommand: String? = null
    var before: (String) -> Unit = {}
    var after: (String) -> Unit = {}
    val commands = mutableListOf<String>()
    val writes: List<String> get() = commands.filter {
        it.startsWith("cmd alarm set-timezone") || it.startsWith("cmd time_zone_detector set_") ||
            it.startsWith("settings put") || it.startsWith("settings delete")
    }

    override fun shell(command: String): ShellResult {
        commands += command
        before(command)
        if (command == rejectedCommand) return ShellResult("", "Permission denied", 1)
        val output = when (command) {
            "cmd alarm help" -> if (alarmAvailable) "Alarm commands:\n  set-timezone TZ\n" else "help only"
            "getprop ro.build.version.sdk" -> sdk.toString()
            "getprop persist.sys.timezone" -> zone
            "cmd time_zone_detector help" -> if (modernAvailable) """
                is_auto_detection_enabled
                set_auto_detection_enabled true|false
                is_telephony_detection_supported
                is_geo_detection_supported
            """.trimIndent() else "help only"
            "cmd time_zone_detector is_telephony_detection_supported" -> telephony.toString()
            "cmd time_zone_detector is_geo_detection_supported" -> geo.toString()
            "cmd time_zone_detector is_auto_detection_enabled", "settings get global auto_time_zone" -> auto
            else -> when {
                command.startsWith("cmd alarm set-timezone ") -> {
                    if (!ignoreZone) zone = command.substringAfterLast(' ').trim('\'')
                    ""
                }
                command.startsWith("cmd time_zone_detector set_auto_detection_enabled ") ||
                    command.startsWith("settings put global auto_time_zone ") -> {
                    if (!ignoreAuto) auto = command.substringAfterLast(' ')
                    ""
                }
                command == "settings delete global auto_time_zone" -> { auto = "null"; "Deleted 1 rows" }
                else -> error("Unexpected command: $command")
            }
        }
        after(command)
        return ShellResult(output, "", 0)
    }

    override fun isAlive() = true
    override fun close() = Unit
}

class TimeZoneRepositoryTest {
    private val device = ZoneDevice()
    private fun apply(zone: String = "Europe/Moscow") = TimeZoneRepository(device, pause = {}).setTimeZone(zone)
    private fun failed() = apply() as TimeZoneUpdateResult.Failed

    @Test fun `invalid IDs never reach the device`() {
        for (zone in listOf("", "Unknown/Zone", "GMT+03:00", "Europe/Moscow;reboot", "UTC'", "$(reboot)")) {
            assertEquals(TimeZoneUpdateResult.Failed(TimeZoneFailure.INVALID_ZONE), apply(zone))
        }
        assertTrue(device.commands.isEmpty())
    }

    @Test fun `modern change disables only automatic zone detection and reads back the zone`() {
        assertEquals(TimeZoneUpdateResult.Applied("Europe/Moscow"), apply(" Europe/Moscow "))
        assertEquals("Europe/Moscow", device.zone)
        assertEquals("false", device.auto)
        assertTrue(device.commands.last().contains("is_auto_detection_enabled"))
        assertFalse(device.commands.any { it.contains("ntp_server") || Regex("(^| )auto_time($| )").containsMatchIn(it) })
    }

    @Test fun `already manual mode does not rewrite automatic settings`() {
        device.auto = "false"
        assertTrue(apply() is TimeZoneUpdateResult.Applied)
        assertEquals(listOf("cmd alarm set-timezone 'Europe/Moscow'"), device.writes)
    }

    @Test fun `TV without detection algorithms can change zone despite a true raw auto flag`() {
        device.telephony = false
        device.geo = false
        assertTrue(apply() is TimeZoneUpdateResult.Applied)
        assertEquals("true", device.auto)
        assertEquals(listOf("cmd alarm set-timezone 'Europe/Moscow'"), device.writes)
    }

    @Test fun `geo detection also requires switching to manual mode`() {
        device.telephony = false
        device.geo = true
        assertTrue(apply() is TimeZoneUpdateResult.Applied)
        assertEquals("false", device.auto)
    }

    @Test fun `unsupported alarm or modern configuration makes no writes`() {
        device.alarmAvailable = false
        assertEquals(TimeZoneFailure.UNSUPPORTED, failed().reason)
        device.alarmAvailable = true
        device.modernAvailable = false
        assertEquals(TimeZoneFailure.UNSUPPORTED, failed().reason)
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `missing shell commands are unsupported before changing settings`() {
        device.rejectedCommand = "cmd alarm help"
        assertEquals(TimeZoneFailure.UNSUPPORTED, failed().reason)
        device.rejectedCommand = "cmd time_zone_detector help"
        assertEquals(TimeZoneFailure.UNSUPPORTED, failed().reason)
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `legacy targets support enabled disabled and absent automatic setting`() {
        device.sdk = 28
        for (value in listOf("0", "1", "null")) {
            device.auto = value
            assertTrue(apply() is TimeZoneUpdateResult.Applied)
            assertEquals("0", device.auto)
        }
        assertFalse(device.commands.any { it.startsWith("cmd time_zone_detector") })
    }

    @Test fun `unknown initial zone or automatic configuration fails before writing`() {
        device.zone = "Unknown/Zone"
        assertEquals(TimeZoneFailure.READ_STATE, failed().reason)
        device.zone = "UTC"
        device.auto = "unknown"
        assertEquals(TimeZoneFailure.READ_STATE, failed().reason)
        assertTrue(device.writes.isEmpty())
    }

    @Test fun `refused automatic mode prevents the zone write`() {
        device.ignoreAuto = true
        val result = failed()
        assertEquals(TimeZoneFailure.AUTO_MODE, result.reason)
        assertEquals(TimeZoneRestoration.RESTORED, result.restoration)
        assertFalse(device.writes.any { it.startsWith("cmd alarm") })
        assertEquals("UTC", device.zone)
    }

    @Test fun `ignored zone write is not a success and restores automatic mode`() {
        device.ignoreZone = true
        val result = failed()
        assertEquals(TimeZoneFailure.WRITE, result.reason)
        assertEquals(TimeZoneRestoration.RESTORED, result.restoration)
        assertEquals("UTC", result.actualZone)
        assertEquals("true", device.auto)
    }

    @Test fun `permission rejection cannot be reported as an applied zone`() {
        device.rejectedCommand = "cmd alarm set-timezone 'Europe/Moscow'"
        val result = failed()
        assertEquals(TimeZoneFailure.WRITE, result.reason)
        assertEquals(TimeZoneRestoration.RESTORED, result.restoration)
        assertEquals("UTC", device.zone)
        assertEquals("true", device.auto)
    }

    @Test fun `delayed device confirmation is awaited`() {
        device.auto = "false"
        device.ignoreZone = true
        var pauses = 0
        val result = TimeZoneRepository(device, pause = { pauses++; device.zone = "Europe/Moscow" })
            .setTimeZone("Europe/Moscow")
        assertEquals(TimeZoneUpdateResult.Applied("Europe/Moscow"), result)
        assertEquals(1, pauses)
    }

    @Test fun `failure after the device applied a command restores both settings`() {
        device.after = { if (it == "cmd alarm set-timezone 'Europe/Moscow'") throw IOException("disconnected") }
        assertEquals(TimeZoneRestoration.RESTORED, failed().restoration)
        assertEquals("UTC", device.zone)
        assertEquals("true", device.auto)
    }

    @Test fun `cancellation restores a partial change and remains cancellation`() {
        device.after = { if (it == "cmd alarm set-timezone 'Europe/Moscow'") throw CancellationException("cancel") }
        assertThrows(CancellationException::class.java) { apply() }
        assertEquals("UTC", device.zone)
        assertEquals("true", device.auto)
    }

    @Test fun `partial automatic change is restored even when its command throws`() {
        device.after = { if (it.endsWith("set_auto_detection_enabled false")) throw IOException("disconnected") }
        assertEquals(TimeZoneRestoration.RESTORED, failed().restoration)
        assertEquals("true", device.auto)
        assertFalse(device.writes.any { it.startsWith("cmd alarm") })
    }

    @Test fun `failed zone restoration still attempts automatic restoration and reports uncertainty`() {
        device.after = { if (it == "cmd alarm set-timezone 'Europe/Moscow'") throw IOException("disconnected") }
        device.before = { if (it == "cmd alarm set-timezone 'UTC'") throw IOException("refused") }
        val result = failed()
        assertEquals(TimeZoneRestoration.UNCONFIRMED, result.restoration)
        assertEquals("Europe/Moscow", result.actualZone)
        assertEquals("true", device.auto)
    }

    @Test fun `legacy missing key is deleted again after failure`() {
        device.sdk = 28
        device.auto = "null"
        device.ignoreZone = true
        assertEquals(TimeZoneRestoration.RESTORED, failed().restoration)
        assertEquals("null", device.auto)
        assertTrue("settings delete global auto_time_zone" in device.writes)
    }

    @Test fun `diagnostic callback cannot prevent restoring device settings`() {
        device.ignoreZone = true
        val result = TimeZoneRepository(device, pause = {}, onFailure = { error("storage failed") })
            .setTimeZone("Europe/Moscow") as TimeZoneUpdateResult.Failed
        assertEquals(TimeZoneRestoration.RESTORED, result.restoration)
        assertEquals("true", device.auto)
    }
}
