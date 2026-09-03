package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.data.isValidNtpServer

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
class DeviceRepository(private val client: AdbClient) {

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
            NtpUpdateResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Собирает сведения об устройстве девятью командами подряд.
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
        val props = parseGetProp(client.shell("getprop").output)
        val uptimeSeconds = parseUptimeSeconds(optional("cat /proc/uptime"))
        val meminfo = optional("cat /proc/meminfo")

        return DeviceInfo(
            model = props["ro.product.model"].orEmpty(),
            manufacturer = props["ro.product.manufacturer"].orEmpty(),
            androidVersion = props["ro.build.version.release"].orEmpty(),
            apiLevel = props["ro.build.version.sdk"].orEmpty(),
            serial = props["ro.serialno"].orEmpty(),
            cpuAbi = props["ro.product.cpu.abi"].orEmpty(),
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
        )
    }

    /** Вывод необязательной команды: пустая строка вместо исключения. */
    private fun optional(command: String): String =
        runCatching { client.shell(command).output }.getOrDefault("")

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
