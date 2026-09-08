package com.civisrom.tvtimefixer.device

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
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
            val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val wrapper = "echo${'$'}{IFS}$encoded|base64${'$'}{IFS}-d|sh"
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
        assertTrue("Missing /data capacity", info.storageTotal.isNotEmpty())
        assertTrue("Missing /data available space", info.storageAvailable.isNotEmpty())
        assertTrue("Missing display modes", info.display.supportedModes.isNotEmpty())
        assertTrue("Missing active display mode", info.display.activeMode.isNotEmpty())
        assertTrue("Missing video declarations", info.videoDecoders.isNotEmpty())
        assertTrue("Missing audio declarations", info.audioDecoders.isNotEmpty())
        before.forEach { (setting, value) -> assertEquals(value, client.shell("settings get global $setting").trimmedOutput) }
    }
}
