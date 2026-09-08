package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.data.isValidNtpServer
import java.util.concurrent.CancellationException

/** Чем закончилась попытка сменить сервер времени. */
sealed interface NtpUpdateResult {
    data class Applied(val server: String) : NtpUpdateResult

    /** Адрес не прошёл проверку формата — до устройства не дошло. */
    data object InvalidServer : NtpUpdateResult

    /**
     * Команда выполнена, но устройство сообщает другое значение.
     *
     * Обычно это значит, что у процесса нет права WRITE_SECURE_SETTINGS,
     * то есть подключение не через adb-shell.
     */
    data class NotConfirmed(val expected: String, val actual: String) : NtpUpdateResult

    data class Failed(val message: String) : NtpUpdateResult
}

/** Ключ системной настройки, ради которой существует вся программа. */
private const val NTP_SETTING = "global ntp_server"

/**
 * Читает и меняет настройки устройства через adb-shell.
 *
 * Логика повторяет десктопную половину, включая обязательное чтение значения
 * обратно: `settings put` завершается успешно и тогда, когда запись не
 * произошла, поэтому доверять коду возврата нельзя.
 */
class DeviceRepository(private val client: AdbClient, private val onFailure: (Exception) -> Unit = {}) {

    fun currentNtpServer(): String = client.shell("settings get $NTP_SETTING").trimmedOutput
        .takeUnless { it == "null" }
        .orEmpty()

    fun setNtpServer(server: String): NtpUpdateResult {
        val value = server.trim()
        if (!isValidNtpServer(value)) return NtpUpdateResult.InvalidServer

        return try {
            client.shell("settings put $NTP_SETTING ${shellQuote(value)}")
            val confirmed = currentNtpServer()
            if (confirmed == value) {
                NtpUpdateResult.Applied(value)
            } else {
                NtpUpdateResult.NotConfirmed(expected = value, actual = confirmed)
            }
        } catch (e: Exception) {
            runCatching { onFailure(e) }
            NtpUpdateResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Собирает сведения при подключении и ручном обновлении, не в фоновом опросе.
     *
     * Обязательна из них только первая: `getprop` заодно проверяет, что связь
     * жива, и её отказ пробрасывается наружу. Остальные необязательны — на
     * конкретной прошивке команды может не быть или она ответит отказом
     * (`dumpsys battery` на приставке без батареи, `wm` на урезанном образе).
     * Раньше любая из них стирала весь экран: исключение уходило в
     * `runCatching{}.getOrNull()` у вызывающего, и человек видел пустой раздел
     * без единого слова о причине.
     */
    fun readDeviceInfo(): DeviceInfo {
        val result = client.shell("getprop")
        check(result.exitCode == 0) { "getprop failed (exit ${result.exitCode})" }
        val props = parseGetProp(result.output)
        check(props.isNotEmpty()) { "getprop returned no device properties" }
        fun prop(key: String) = props[key]?.trim()?.takeUnless { it in listOf("unknown", "null") }.orEmpty()
        val uptimeSeconds = parseUptimeSeconds(optional("cat /proc/uptime"))
        val meminfo = optional("cat /proc/meminfo")
        val storage = parseDataStorage(optional("df -k /data"))
        val display = parseDisplayDetails(optional("dumpsys display"))
        val audio = parseAudioOutputs(optional("dumpsys media.audio_policy"))
        val decoders = parseDeclaredDecoders(optional(READ_CODEC_XML_COMMAND))

        return DeviceInfo(
            model = props["ro.product.model"].orEmpty(),
            manufacturer = props["ro.product.manufacturer"].orEmpty(),
            androidVersion = props["ro.build.version.release"].orEmpty(),
            apiLevel = props["ro.build.version.sdk"].orEmpty(),
            serial = props["ro.serialno"].orEmpty(),
            cpuAbi = prop("ro.product.cpu.abilist").ifEmpty { prop("ro.product.cpu.abi") },
            timezone = props["persist.sys.timezone"].orEmpty(),
            locale = props["persist.sys.locale"].orEmpty(),
            currentNtpServer = optional("settings get $NTP_SETTING").trim()
                .takeUnless { it == "null" }
                .orEmpty(),
            batteryLevel = parseBatteryLevel(optional("dumpsys battery")),
            totalRam = parseMemInfo(meminfo, "MemTotal"),
            availableRam = parseMemInfo(meminfo, "MemAvailable"),
            screenResolution = optional("wm size").trim(),
            screenDensity = optional("wm density").trim(),
            cpuCores = optional("cat /proc/cpuinfo | grep \"^processor\" | wc -l").trim(),
            kernelVersion = optional("uname -r").trim(),
            uptime = uptimeSeconds?.let(::formatUptime).orEmpty(),
            buildDisplay = prop("ro.build.display.id"),
            securityPatch = prop("ro.build.version.security_patch"),
            vendorSecurityPatch = prop("ro.vendor.build.security_patch"),
            buildFingerprint = prop("ro.build.fingerprint"),
            buildType = prop("ro.build.type"),
            bootloader = prop("ro.bootloader"),
            deviceCode = prop("ro.product.device"),
            socModel = prop("ro.soc.model"),
            socManufacturer = prop("ro.soc.manufacturer"),
            hardware = listOf(prop("ro.hardware"), prop("ro.board.platform")).filter(String::isNotEmpty).distinct().joinToString(" / "),
            gpu = optional("dumpsys SurfaceFlinger | grep '^GLES:'").lineSequence()
                .firstOrNull { it.startsWith("GLES:") }?.substringAfter("GLES:")?.trim().orEmpty(),
            storageTotal = storage.first,
            storageAvailable = storage.second,
            automaticTime = parseAutomaticSetting(optional("settings get global auto_time")),
            automaticTimeZone = automaticTimeZoneEnabled(prop("ro.build.version.sdk").toIntOrNull()),
            display = display,
            audioOutputs = audio.first,
            audioFormats = audio.second,
            videoDecoders = decoders.first,
            audioDecoders = decoders.second,
            networkAddresses = parseNetworkAddresses(optional("ip -o addr show scope global")),
        )
    }

    /** На TV Android 12+ raw auto_time_zone=1 не означает наличия автоопределения. */
    fun automaticTimeZoneEnabled(apiLevel: Int?): Boolean? {
        if (apiLevel == null) return null
        if (apiLevel < 31) return parseAutomaticSetting(optional("settings get global auto_time_zone"))
        val telephony = optional("cmd time_zone_detector is_telephony_detection_supported").trim().toBooleanStrictOrNull()
        val geo = optional("cmd time_zone_detector is_geo_detection_supported").trim().toBooleanStrictOrNull()
        if (telephony == false && geo == false) return false
        if (telephony != true && geo != true) return null
        return optional("cmd time_zone_detector is_auto_detection_enabled").trim().toBooleanStrictOrNull()
    }

    /** Вывод необязательной команды: пустая строка вместо исключения. */
    private fun optional(command: String): String = try {
        client.shell(command).let { if (it.exitCode == 0) it.output else "" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }

    private companion object {
        /**
         * Экранирует значение для оболочки устройства.
         *
         * Адрес уже прошёл isValidNtpServer, поэтому опасных символов там быть
         * не может, но команда собирается конкатенацией — оставлять её без
         * кавычек значит зависеть от того, что проверка никогда не ослабнет.
         */
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
