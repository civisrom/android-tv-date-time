package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.CancellationException

val availableTimeZoneIds: Set<String> by lazy { TimeZone.getAvailableIDs().toSet() }

fun isValidTimeZoneId(value: String): Boolean =
    value in availableTimeZoneIds && value.matches(Regex("[A-Za-z0-9_+./-]+"))

enum class TimeZoneFailure { INVALID_ZONE, UNSUPPORTED, READ_STATE, AUTO_MODE, WRITE }
enum class TimeZoneRestoration { NOT_NEEDED, RESTORED, UNCONFIRMED }

sealed interface TimeZoneUpdateResult {
    data class Applied(val zoneId: String) : TimeZoneUpdateResult
    data class Failed(
        val reason: TimeZoneFailure,
        val restoration: TimeZoneRestoration = TimeZoneRestoration.NOT_NEEDED,
        val actualZone: String? = null,
    ) : TimeZoneUpdateResult
}

/** Меняет только пояс и его auto-режим через авторизованный ADB shell. Вызывать вне UI. */
class TimeZoneRepository(
    private val client: AdbClient,
    private val pause: () -> Unit = { Thread.sleep(100) },
    private val onFailure: (Exception) -> Unit = {},
) {
    private enum class AutoKind { LEGACY, MODERN, UNSUPPORTED }
    private data class AutoMode(val kind: AutoKind, val value: String) {
        val disabledValue: String get() = if (kind == AutoKind.MODERN) "false" else "0"
        val manual: Boolean get() = kind == AutoKind.UNSUPPORTED || value == disabledValue
    }
    private data class Snapshot(val zone: String, val auto: AutoMode)

    fun setTimeZone(input: String): TimeZoneUpdateResult {
        val zone = input.trim()
        if (!isValidTimeZoneId(zone)) return TimeZoneUpdateResult.Failed(TimeZoneFailure.INVALID_ZONE)
        var before: Snapshot? = null
        var autoAttempted = false
        var zoneAttempted = false
        var reason = TimeZoneFailure.READ_STATE
        try {
            val alarmHelp = client.shell("cmd alarm help")
            // AOSP handleDefaultCommands печатает help и возвращает -1 (в shell — 255).
            // Наличие команды определяем по справке; записи ниже требуют успешного кода.
            if (alarmHelp.errorOutput.isNotBlank() ||
                !hasCommand(alarmHelp.output, "set-timezone")) {
                return TimeZoneUpdateResult.Failed(TimeZoneFailure.UNSUPPORTED)
            }
            val sdk = read("getprop ro.build.version.sdk").toIntOrNull()
                ?: throw IOException("Unknown target API")
            if (sdk <= 0) throw IOException("Unknown target API")
            val auto = readAutoMode(sdk)
                ?: return TimeZoneUpdateResult.Failed(TimeZoneFailure.UNSUPPORTED)
            before = Snapshot(readZone(), auto)
            if (!auto.manual) {
                reason = TimeZoneFailure.AUTO_MODE
                autoAttempted = true
                writeAuto(auto, auto.disabledValue)
                checkEventually { readAutoValue(auto.kind) == auto.disabledValue }
            }
            reason = TimeZoneFailure.WRITE
            zoneAttempted = true
            writeZone(zone)
            checkEventually { readZone() == zone && (auto.kind == AutoKind.UNSUPPORTED ||
                readAutoValue(auto.kind) == auto.disabledValue) }
            return TimeZoneUpdateResult.Applied(zone)
        } catch (error: Exception) {
            runCatching { onFailure(error) }
            val snapshot = before
            val changed = autoAttempted || zoneAttempted
            val restoration = if (!changed || snapshot == null) TimeZoneRestoration.NOT_NEEDED else {
                // Каждую часть восстанавливаем независимо: отказ одной не мешает второй.
                if (zoneAttempted) runCatching { writeZone(snapshot.zone) }
                if (autoAttempted) runCatching { writeAuto(snapshot.auto, snapshot.auto.value) }
                val restored = runCatching {
                    checkEventually { readZone() == snapshot.zone &&
                        readAutoValue(snapshot.auto.kind) == snapshot.auto.value }
                }.isSuccess
                if (restored) TimeZoneRestoration.RESTORED else TimeZoneRestoration.UNCONFIRMED
            }
            if (error is CancellationException) throw error
            return TimeZoneUpdateResult.Failed(reason, restoration,
                if (snapshot == null) null else runCatching { readZone() }.getOrNull())
        }
    }

    private fun readAutoMode(sdk: Int): AutoMode? {
        if (sdk < 31) return AutoMode(AutoKind.LEGACY, readAutoValue(AutoKind.LEGACY))
        val response = client.shell("cmd time_zone_detector help")
        if (response.errorOutput.isNotBlank()) return null
        val help = response.output
        val commands = listOf("is_auto_detection_enabled", "set_auto_detection_enabled",
            "is_telephony_detection_supported", "is_geo_detection_supported")
        if (commands.any { !hasCommand(help, it) }) return null
        val telephony = readBoolean("cmd time_zone_detector is_telephony_detection_supported")
        val geo = readBoolean("cmd time_zone_detector is_geo_detection_supported")
        // На TV без обоих алгоритмов режим всегда ручной, даже при raw auto_time_zone=1.
        if (!telephony && !geo) return AutoMode(AutoKind.UNSUPPORTED, "")
        return AutoMode(AutoKind.MODERN, readAutoValue(AutoKind.MODERN))
    }

    private fun readAutoValue(kind: AutoKind): String = when (kind) {
        AutoKind.UNSUPPORTED -> ""
        AutoKind.MODERN -> readBoolean("cmd time_zone_detector is_auto_detection_enabled").toString()
        AutoKind.LEGACY -> read("settings get global auto_time_zone").also {
            if (it !in listOf("0", "1", "null")) throw IOException("Unknown automatic zone mode")
        }
    }

    private fun writeAuto(mode: AutoMode, value: String) {
        when (mode.kind) {
            AutoKind.MODERN -> read("cmd time_zone_detector set_auto_detection_enabled $value")
            AutoKind.LEGACY -> if (value == "null") read("settings delete global auto_time_zone")
                else read("settings put global auto_time_zone $value")
            AutoKind.UNSUPPORTED -> Unit
        }
    }

    private fun readBoolean(command: String): Boolean = when (read(command)) {
        "true" -> true
        "false" -> false
        else -> throw IOException("Unknown service response")
    }

    private fun readZone(): String = read("getprop persist.sys.timezone").also {
        if (!isValidTimeZoneId(it)) throw IOException("Unknown target time zone")
    }

    private fun writeZone(zone: String) {
        // Значение из проверенного справочника, включая восстановление старого пояса.
        require(isValidTimeZoneId(zone))
        read("cmd alarm set-timezone '$zone'")
    }

    private fun read(command: String): String {
        val result = client.shell(command)
        if (result.exitCode != 0 || result.errorOutput.isNotBlank()) throw IOException("Shell command rejected")
        return result.trimmedOutput
    }

    private fun checkEventually(check: () -> Boolean) {
        repeat(5) { attempt ->
            if (check()) return
            if (attempt < 4) pause()
        }
        throw IOException("Device did not confirm the change")
    }

    private fun hasCommand(help: String, command: String): Boolean =
        Regex("(?m)^\\s*${Regex.escape(command)}(?:\\s|$)").containsMatchIn(help)
}
