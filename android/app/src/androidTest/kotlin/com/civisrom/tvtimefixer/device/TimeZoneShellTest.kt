package com.civisrom.tvtimefixer.device

import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.*
import org.junit.Test

/** Настоящие системные службы эмулятора; исходные настройки восстанавливаются в finally. */
@SdkSuppress(minSdkVersion = 31)
class TimeZoneShellTest {
    private val client = object : AdbClient {
        override fun shell(command: String): ShellResult {
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    // UiAutomation.exec(String) не разбирает shell-кавычки: передаём скрипт через stdin.
                    it.write(("exec 2>&1\n$command\nzone_status=${'$'}?\nprintf '\\n__tv_zone_exit=%s\\n' \"${'$'}zone_status\"\nexit\n").toByteArray())
                }
                input.bufferedReader().readText()
            }
            val marker = "__tv_zone_exit="
            val index = output.lastIndexOf(marker)
            check(index >= 0) { "Missing shell exit status" }
            return ShellResult(output.substring(0, index).trimEnd(), "", output.substring(index + marker.length).trim().toInt())
        }
        override fun isAlive() = true
        override fun close() = Unit
    }

    private fun read(command: String): String = client.shell(command).let {
        assertEquals("Shell command failed: $command", 0, it.exitCode)
        it.trimmedOutput
    }

    @Test fun system_time_zone_change_is_confirmed_without_changing_NTP_settings() {
        val originalZone = read("getprop persist.sys.timezone")
        assertTrue(isValidTimeZoneId(originalZone))
        val originalAutoZone = read("cmd time_zone_detector is_auto_detection_enabled")
        assertTrue(originalAutoZone in listOf("true", "false"))
        val originalAutoTime = read("settings get global auto_time")
        val originalNtp = read("settings get global ntp_server")
        val supported = read("cmd time_zone_detector is_telephony_detection_supported") == "true" ||
            read("cmd time_zone_detector is_geo_detection_supported") == "true"
        val selected = if (originalZone == "Europe/Moscow") "UTC" else "Europe/Moscow"
        try {
            assertEquals(TimeZoneUpdateResult.Applied(selected), TimeZoneRepository(client).setTimeZone(selected))
            assertEquals(selected, read("getprop persist.sys.timezone"))
            if (supported) assertEquals("false", read("cmd time_zone_detector is_auto_detection_enabled"))
            assertEquals(originalAutoTime, read("settings get global auto_time"))
            assertEquals(originalNtp, read("settings get global ntp_server"))
        } finally {
            try {
                assertEquals(TimeZoneUpdateResult.Applied(originalZone), TimeZoneRepository(client).setTimeZone(originalZone))
            } finally {
                if (supported) read("cmd time_zone_detector set_auto_detection_enabled $originalAutoZone")
            }
            assertEquals(originalAutoZone, read("cmd time_zone_detector is_auto_detection_enabled"))
            // При возвращении авто-режима Android вправе принять новую подсказку сети.
            if (!supported || originalAutoZone == "false") assertEquals(originalZone, read("getprop persist.sys.timezone"))
        }
    }
}
