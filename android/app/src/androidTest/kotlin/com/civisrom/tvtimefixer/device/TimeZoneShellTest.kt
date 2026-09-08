package com.civisrom.tvtimefixer.device

import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.*
import org.junit.Test

/** Настоящие системные службы эмулятора; исходные настройки восстанавливаются в finally. */
@SdkSuppress(minSdkVersion = 28)
class TimeZoneShellTest {
    private val client = object : AdbClient {
        override fun shell(command: String): ShellResult {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // UiAutomation.exec(String) не разбирает кавычки, а Rw требует API 31.
            // Передаём sh -c одним аргументом: пробелы команд раскрывает сама оболочка.
            // Только фиксированные команды теста/репозитория; значения пояса не содержат пробелов.
            // Это не требует доступа shell к scoped storage тестового приложения на API 30.
            val script = "(${command.replace(" ", "${'$'}{IFS}")};zone_status=${'$'}?;" +
                "printf${'$'}{IFS}'\\n__tv_zone_exit=%s\\n'${'$'}{IFS}${'$'}zone_status)2>&1"
            check(script.none { it.isWhitespace() })
            val pipe = instrumentation.uiAutomation.executeShellCommand("sh -c $script")
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                input.bufferedReader().readText()
            }
            val marker = "__tv_zone_exit="
            val index = output.lastIndexOf(marker)
            check(index >= 0) { "Missing shell exit status: ${output.take(200)}" }
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
        val modern = Build.VERSION.SDK_INT >= 31
        val autoQuery = if (modern) "cmd time_zone_detector is_auto_detection_enabled" else "settings get global auto_time_zone"
        val originalAutoZone = read(autoQuery)
        assertTrue(originalAutoZone in if (modern) listOf("true", "false") else listOf("0", "1", "null"))
        val originalAutoTime = read("settings get global auto_time")
        val originalNtp = read("settings get global ntp_server")
        val supported = !modern || read("cmd time_zone_detector is_telephony_detection_supported") == "true" ||
            read("cmd time_zone_detector is_geo_detection_supported") == "true"
        val selected = if (originalZone == "Pacific/Honolulu") "UTC" else "Pacific/Honolulu"
        var failure: Throwable? = null
        try {
            assertEquals(TimeZoneUpdateResult.Applied(selected), TimeZoneRepository(client).setTimeZone(selected))
            assertEquals(selected, read("getprop persist.sys.timezone"))
            if (supported) assertEquals(if (modern) "false" else "0", read(autoQuery))
            assertEquals(originalAutoTime, read("settings get global auto_time"))
            assertEquals(originalNtp, read("settings get global ntp_server"))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                try {
                    assertEquals(TimeZoneUpdateResult.Applied(originalZone), TimeZoneRepository(client).setTimeZone(originalZone))
                } finally {
                    if (supported) read(if (modern) "cmd time_zone_detector set_auto_detection_enabled $originalAutoZone"
                        else if (originalAutoZone == "null") "settings delete global auto_time_zone"
                        else "settings put global auto_time_zone $originalAutoZone")
                }
                assertEquals(originalAutoZone, read(autoQuery))
                // При возвращении авто-режима Android вправе принять новую подсказку сети.
                if (!supported || originalAutoZone in listOf("0", "false")) assertEquals(originalZone, read("getprop persist.sys.timezone"))
            } catch (restoreError: Throwable) {
                if (failure == null) throw restoreError else failure.addSuppressed(restoreError)
            }
        }
    }
}
