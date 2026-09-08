package com.civisrom.tvtimefixer.diagnostics

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.data.ScanProgress
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.device.isValidTimeZoneId

/** Технические сведения одной операции. Произвольный вывод, адреса и коды не сохраняются. */
class OperationTrace {
    private val entries = mutableListOf<String>()
    private var truncated = false

    private fun append(value: String) {
        entries.add(value.take(1800))
        while (entries.sumOf { it.length + 1 } > 1800) {
            entries.removeAt(0)
            truncated = true
        }
    }

    internal fun details(): String =
        (if (truncated) "trace.truncated=true\n" else "") + entries.joinToString("\n")

    fun exception(error: Throwable) = append(safeExceptionDetails(error).take(700))

    fun ntp(result: NtpProbeResult) = append(
        "ntp.reachable=${result.reachable}; success_pct=${result.successRate}; " +
            "rtt_ms=${result.avgRttMs}; median_ms=${result.medianRttMs}; jitter_ms=${result.rttJitterMs}; " +
            "offset_s=${result.offsetSeconds}; failure=${result.failure ?: "none"}",
    )

    fun deviceTime(result: DeviceTimeCheck) = append(
        "clock.status=${result.status}; auto_time=${result.automaticTime}; " +
            "difference_s=${result.differenceSeconds}; uncertainty_s=${result.uncertaintySeconds}",
    )

    fun scan(progress: ScanProgress) {
        append("scan.checked=${progress.checked}; total=${progress.total}; selected=${progress.best.size}; " +
            "attempts_per_server=5; minimum_success_pct=80")
        progress.best.forEach(::ntp)
    }

    fun ntpUpdate(result: NtpUpdateResult) = append(when (result) {
        is NtpUpdateResult.Applied -> "ntp.write_confirmed=true"
        is NtpUpdateResult.NotConfirmed -> "ntp.write_confirmed=false; response=mismatched_value"
        is NtpUpdateResult.InvalidServer -> "ntp.write_attempted=false; reason=invalid_address"
        is NtpUpdateResult.Failed -> "ntp.write_confirmed=false; response=exception"
    })

    fun timeZone(result: TimeZoneUpdateResult) = append(when (result) {
        is TimeZoneUpdateResult.Applied -> "timezone.applied=true"
        is TimeZoneUpdateResult.Failed -> "timezone.failure=${result.reason}; restoration=${result.restoration}"
    })

    /** Декоратор не владеет транспортом и не выполняет дополнительных команд. */
    fun client(delegate: AdbClient): AdbClient = object : AdbClient {
        override fun isAlive() = delegate.isAlive()
        override fun close() = delegate.close()
        override fun shell(command: String): ShellResult {
            val started = System.nanoTime()
            try {
                val result = delegate.shell(command)
                append("shell=${commandLabel(command)}; exit=${result.exitCode}; " +
                    "ms=${(System.nanoTime() - started) / 1_000_000}; " +
                    "out_chars=${result.output.length}; err_chars=${result.errorOutput.length}" +
                    responseDetails(command, result))
                return result
            } catch (error: Exception) {
                append("shell=${commandLabel(command)}; response=exception; " +
                    "ms=${(System.nanoTime() - started) / 1_000_000}")
                exception(error)
                throw error
            }
        }
    }

    private fun commandLabel(command: String): String = when {
        command in READ_COMMANDS -> command
        command.startsWith("settings put global ntp_server ") -> "settings put global ntp_server <server>"
        command.startsWith("cmd alarm set-timezone ") -> "cmd alarm set-timezone <zone>"
        command.startsWith("settings put global auto_time_zone ") -> "settings put global auto_time_zone <mode>"
        command == "settings delete global auto_time_zone" -> command
        command.startsWith("cmd time_zone_detector set_auto_detection_enabled ") ->
            "cmd time_zone_detector set_auto_detection_enabled <mode>"
        else -> "other"
    }

    private fun responseDetails(command: String, result: ShellResult): String = buildString {
        val output = result.trimmedOutput
        val diagnosticText = (result.errorOutput.take(4096) + result.output.take(4096)).lowercase()
        val error = when {
            "permission denied" in diagnosticText || "permission denial" in diagnosticText ||
                "securityexception" in diagnosticText -> "permission_denied"
            "unknown command" in diagnosticText -> "unknown_command"
            "can't find service" in diagnosticText -> "service_missing"
            "not found" in diagnosticText -> "not_found"
            else -> null
        }
        error?.let { append("; error=$it") }
        when {
            command == "cmd alarm help" -> append("; set-timezone=${hasCommand(output, "set-timezone")}")
            command == "cmd time_zone_detector help" -> {
                val available = ZONE_COMMANDS.filter { hasCommand(output, it) }
                append("; commands=${available.joinToString(",")}")
            }
            command == "getprop ro.build.version.sdk" && output.matches(Regex("[0-9]{1,3}")) ->
                append("; target_api=$output")
            command == "getprop" -> {
                val sdk = Regex("(?m)^\\[ro.build.version.sdk\\]: \\[([0-9]{1,3})\\]$")
                    .find(output)?.groupValues?.get(1)
                append("; target_api=${sdk ?: "unknown"}")
            }
            command == "getprop persist.sys.timezone" && isValidTimeZoneId(output) ->
                append("; zone=$output")
            command in READ_COMMANDS && output in listOf("true", "false", "0", "1", "null") ->
                append("; value=$output")
            command == "date +%s" && output.toLongOrNull()?.let { it in 0L..253_402_300_799L } == true ->
                append("; epoch_s=$output")
        }
    }

    private fun hasCommand(output: String, command: String): Boolean =
        Regex("(?m)^\\s*${Regex.escape(command)}(?:\\s|$)").containsMatchIn(output)

    private companion object {
        val ZONE_COMMANDS = listOf("is_auto_detection_enabled", "set_auto_detection_enabled",
            "is_telephony_detection_supported", "is_geo_detection_supported")
        val READ_COMMANDS = setOf("getprop", "getprop ro.build.version.sdk", "getprop persist.sys.timezone",
            "settings get global ntp_server", "settings get global auto_time", "settings get global auto_time_zone",
            "cmd alarm help", "cmd time_zone_detector help", "date +%s", "cat /proc/uptime", "cat /proc/meminfo",
            "dumpsys battery", "wm size", "wm density", "uname -r", "cat /proc/cpuinfo | grep \"^processor\" | wc -l") +
            ZONE_COMMANDS.map { "cmd time_zone_detector $it" }
    }
}
