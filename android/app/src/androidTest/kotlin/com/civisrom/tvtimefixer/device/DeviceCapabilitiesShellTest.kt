package com.civisrom.tvtimefixer.device

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import org.junit.Assert.*
import org.junit.Test

/** Проверяет настоящий вывод служб Android 6/11/16, не меняя настройки устройства. */
class DeviceCapabilitiesShellTest {
    private val client = object : AdbClient {
        override fun isAlive() = true
        override fun close() = Unit
        override fun shell(command: String): ShellResult {
            val script = "($command); tvtf_status=${'$'}?; printf '\\n__tvtf_status=%s\\n' \"${'$'}tvtf_status\""
            // executeShellCommand(String) делит аргументы по пробелам. Кодирование сохраняет
            // циклы и кавычки исходного скрипта и не требует файла на устройстве.
            // В Android 6 ещё нет утилиты base64; printf есть и в старом mksh.
            val escaped = script.toByteArray(Charsets.UTF_8).joinToString("") {
                "\\${(it.toInt() and 0xff).toString(8).padStart(3, '0')}"
            }
            val wrapper = "printf${'$'}{IFS}'$escaped'|sh"
            val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("sh -c $wrapper")
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.bufferedReader().readText() }
            val index = output.lastIndexOf("__tvtf_status=")
            check(index >= 0) { "Missing shell status" }
            return ShellResult(output.substring(0, index).trimEnd(), "", output.substring(index).substringAfter('=').trim().toInt())
        }
    }

    @Test fun reads_real_device_details_without_changing_time_settings() {
        val before = listOf("auto_time", "auto_time_zone", "ntp_server").associateWith {
            client.shell("settings get global $it").trimmedOutput
        }
        val info = DeviceRepository(client).readDeviceInfo()
        assertEquals(Build.MODEL, info.model)
        assertEquals(Build.VERSION.SDK_INT.toString(), info.apiLevel)
        assertTrue("Missing firmware build", info.buildDisplay.isNotEmpty())
        val missing = mapOf(
            "/data capacity" to info.storageTotal,
            "/data available space" to info.storageAvailable,
            "display modes" to info.display.supportedModes,
            "active display mode" to info.display.activeMode,
            "video declarations" to info.videoDecoders,
            "audio declarations" to info.audioDecoders,
        ).filterValues { it.isEmpty() }.keys
        assertTrue("Missing device details: $missing; df=${client.shell("df -k /data").trimmedOutput}", missing.isEmpty())
        before.forEach { (setting, value) -> assertEquals(value, client.shell("settings get global $setting").trimmedOutput) }
    }
}
