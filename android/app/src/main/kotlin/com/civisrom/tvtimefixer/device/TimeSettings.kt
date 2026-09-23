package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import com.civisrom.tvtimefixer.data.isValidNtpServer
import com.civisrom.tvtimefixer.data.usableDeviceSerial
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

enum class TimeSetting { NTP, AUTO_TIME, AUTO_TIME_ZONE, TIME_ZONE }
enum class TimeSettingStatus { VERIFIED, UNAVAILABLE, PERMISSION_DENIED, UNSUPPORTED, NOT_CONFIRMED, FAILED, INVALID }
enum class DeviceIdentityKind { SERIAL, ANDROID_ID }

/** A local binding to reported device identity, never an IP address or ADB credential. */
data class TimeDeviceIdentity(val digest: String, val kind: DeviceIdentityKind)

fun timeDeviceIdentity(properties: Map<String, String>, androidId: String?): TimeDeviceIdentity? {
    fun usable(value: String) = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    val model = properties["ro.product.model"].orEmpty().trim()
    val maker = properties["ro.product.manufacturer"].orEmpty().trim()
    val device = properties["ro.product.device"].orEmpty().trim()
    if (!usable(model) || (!usable(maker) && !usable(device))) return null
    val serial = listOf("ro.serialno", "ro.boot.serialno").map { properties[it].orEmpty().trim() }
        .firstOrNull { usableDeviceSerial(it) && it.any { c -> c != '0' } }
    val id = androidId?.trim()?.lowercase()?.takeIf {
        it.matches(Regex("[0-9a-f]{16}")) && it != "0000000000000000" && it != "9774d56d682e549c"
    }
    val kind = if (serial != null) DeviceIdentityKind.SERIAL else DeviceIdentityKind.ANDROID_ID
    val value = serial ?: id ?: return null
    val bytes = listOf(kind.name, value, maker, model, device).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return TimeDeviceIdentity(digest, kind)
}

/** Values preserve the setting's raw text; the literal "null" denotes an absent Android setting. */
data class TimeSettings(val values: Map<TimeSetting, String>, val effectiveAutoZone: Boolean? = null) {
    operator fun get(setting: TimeSetting): String? = values[setting]
    val complete: Boolean get() = TimeSetting.entries.all { values[it] != null }
}

data class TimeSettingsRead(
    val identity: TimeDeviceIdentity?,
    val settings: TimeSettings,
    val statuses: Map<TimeSetting, TimeSettingStatus>,
    val apiLevel: Int? = null,
    val consistent: Boolean = true,
) {
    val capturable: Boolean get() = identity != null && settings.complete && consistent &&
        statuses.values.all { it == TimeSettingStatus.VERIFIED }
}

data class TimeSettingsChange(
    val statuses: Map<TimeSetting, TimeSettingStatus> = emptyMap(),
    val identityMatches: Boolean = true,
    val rollback: Map<TimeSetting, TimeSettingStatus>? = null,
) {
    val confirmed: Boolean get() = identityMatches && statuses.isNotEmpty() &&
        statuses.values.all { it == TimeSettingStatus.VERIFIED }
}

/** New lists use Android 14's URI format; captured raw settings never pass through this formatter. */
fun ntpSettingForHosts(hosts: List<String>, apiLevel: Int?): String? {
    val values = hosts.map(String::trim)
    if (values.size !in 1..4 || values.any { !isValidNtpServer(it) } ||
        values.map(String::lowercase).distinct().size != values.size) return null
    if (values.size == 1) return values.single()
    if (apiLevel == null || apiLevel < 34) return null
    return values.joinToString("|") { "ntp://" + if (':' in it) "[$it]" else it }
}

internal fun validTimeSetting(setting: TimeSetting, value: String): Boolean = when (setting) {
    TimeSetting.NTP -> value.length <= 4096 && value.none(Char::isISOControl) &&
        ("shell,v2,raw:settings put global ntp_server '" + value.replace("'", "'\\''") + "'").toByteArray(Charsets.UTF_8).size < 4096
    TimeSetting.AUTO_TIME, TimeSetting.AUTO_TIME_ZONE -> value in setOf("0", "1", "null")
    TimeSetting.TIME_ZONE -> isSafeCapturedTimeZone(value)
}

/** Reads / writes only the four allowlisted settings. Run outside the UI thread and under the device mutex. */
class TimeSettingsRepository(private val client: AdbClient, private val pause: () -> Unit = { Thread.sleep(100) }) {
    private data class Read(val value: String?, val status: TimeSettingStatus)

    private fun checked(command: String): Pair<ShellResult?, TimeSettingStatus> = try {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Time settings read cancelled")
        val result = client.shell(command)
        result to when {
            result.permissionDenied -> TimeSettingStatus.PERMISSION_DENIED
            result.exitCode != 0 || result.errorOutput.isNotBlank() -> TimeSettingStatus.FAILED
            result.output.length > 65_536 -> TimeSettingStatus.UNAVAILABLE
            else -> TimeSettingStatus.VERIFIED
        }
    } catch (error: CancellationException) { throw error
    } catch (error: InterruptedException) { throw CancellationException("Time settings read cancelled", error)
    } catch (_: Exception) { null to TimeSettingStatus.UNAVAILABLE }

    private fun properties(): Map<String, String> {
        val (result, status) = checked("getprop")
        return if (status == TimeSettingStatus.VERIFIED) parseGetProp(result!!.output) else emptyMap()
    }

    private fun identity(props: Map<String, String>): TimeDeviceIdentity? {
        timeDeviceIdentity(props, null)?.let { return it }
        val (result, status) = checked("settings get secure android_id")
        return timeDeviceIdentity(props, result?.trimmedOutput.takeIf { status == TimeSettingStatus.VERIFIED })
    }

    fun readIdentity(): TimeDeviceIdentity? = identity(properties())

    fun read(): TimeSettingsRead {
        val props = properties()
        val api = props["ro.build.version.sdk"]?.toIntOrNull()
        val read = TimeSetting.entries.associateWith(::readSetting)
        return TimeSettingsRead(identity(props), TimeSettings(read.mapNotNull { (key, value) ->
            value.value?.let { key to it }
        }.toMap(), DeviceRepository(client).automaticTimeZoneEnabled(api)),
            read.mapValues { it.value.status }, api)
    }

    /** Refuse a baseline that changed while it was being read. Nothing is written here. */
    fun capture(): TimeSettingsRead {
        val first = read()
        if (!first.capturable) return first
        val second = read()
        return second.copy(consistent = second.capturable && first.identity == second.identity &&
            first.settings == second.settings)
    }

    fun apply(identity: TimeDeviceIdentity, desired: TimeSettings, rollbackOnFailure: Boolean = false): TimeSettingsChange {
        val before = read()
        if (before.identity != identity) return TimeSettingsChange(identityMatches = false)
        if (rollbackOnFailure && !before.capturable) return TimeSettingsChange(before.statuses)
        val result = writeAll(identity, desired)
        if (result.confirmed || !rollbackOnFailure || !result.identityMatches) return result
        val rollback = writeAll(identity, before.settings)
        return result.copy(rollback = if (rollback.identityMatches) rollback.statuses else
            TimeSetting.entries.associateWith { TimeSettingStatus.UNAVAILABLE })
    }

    fun applyNtpHosts(identity: TimeDeviceIdentity, hosts: List<String>): TimeSettingsChange {
        val before = read()
        if (before.identity != identity) return TimeSettingsChange(identityMatches = false)
        val raw = ntpSettingForHosts(hosts, before.apiLevel) ?: return TimeSettingsChange(
            mapOf(TimeSetting.NTP to if (hosts.size > 1 && (before.apiLevel ?: 0) < 34)
                TimeSettingStatus.UNSUPPORTED else TimeSettingStatus.INVALID))
        val original = before.settings[TimeSetting.NTP] ?: return TimeSettingsChange(
            mapOf(TimeSetting.NTP to before.statuses.getValue(TimeSetting.NTP)))
        val result = writeAll(identity, TimeSettings(mapOf(TimeSetting.NTP to raw)))
        if (result.confirmed || !result.identityMatches) return result
        val rollback = writeAll(identity, TimeSettings(mapOf(TimeSetting.NTP to original)))
        return result.copy(rollback = if (rollback.identityMatches) rollback.statuses else
            mapOf(TimeSetting.NTP to TimeSettingStatus.UNAVAILABLE))
    }

    private fun writeAll(identity: TimeDeviceIdentity, settings: TimeSettings): TimeSettingsChange {
        val result = linkedMapOf<TimeSetting, TimeSettingStatus>()
        // Zone changes can temporarily disable automatic zone detection; restore both flags last.
        for (field in listOf(TimeSetting.TIME_ZONE, TimeSetting.NTP, TimeSetting.AUTO_TIME, TimeSetting.AUTO_TIME_ZONE)) {
            val value = settings[field] ?: continue
            if (!validTimeSetting(field, value)) { result[field] = TimeSettingStatus.INVALID; continue }
            if (readIdentity() != identity) return TimeSettingsChange(result, identityMatches = false)
            result[field] = write(field, value, settings.effectiveAutoZone)
        }
        return TimeSettingsChange(result)
    }

    private fun write(field: TimeSetting, value: String, effectiveAutoZone: Boolean?): TimeSettingStatus {
        if (field == TimeSetting.TIME_ZONE) {
            if (readSetting(field).value == value) return TimeSettingStatus.VERIFIED
            val (help, status) = checked("cmd alarm help")
            if (status == TimeSettingStatus.PERMISSION_DENIED) return status
            if (help == null) return TimeSettingStatus.UNAVAILABLE
            val changed = TimeZoneRepository(client, pause).restoreTimeZone(value)
            return when (changed) {
                is TimeZoneUpdateResult.Applied -> TimeSettingStatus.VERIFIED
                is TimeZoneUpdateResult.Failed -> if (changed.reason == TimeZoneFailure.UNSUPPORTED)
                    TimeSettingStatus.UNSUPPORTED else TimeSettingStatus.NOT_CONFIRMED
            }
        }
        val command = if (value == "null") "settings delete global ${key(field)}"
            else "settings put global ${key(field)} '${value.replace("'", "'\\''")}'"
        val (_, status) = checked(command)
        if (status != TimeSettingStatus.VERIFIED) return status
        var read = Read(null, TimeSettingStatus.UNAVAILABLE)
        for (attempt in 0..4) {
            read = readSetting(field)
            if (read.value == value) break
            if (attempt < 4) pause()
        }
        if (read.status != TimeSettingStatus.VERIFIED) return read.status
        if (read.value != value) return TimeSettingStatus.NOT_CONFIRMED
        if (field == TimeSetting.AUTO_TIME_ZONE && effectiveAutoZone != null) {
            val props = properties()
            val api = props["ro.build.version.sdk"]?.toIntOrNull()
            val actual = DeviceRepository(client).automaticTimeZoneEnabled(api)
            if (actual != effectiveAutoZone) {
                if (api == null || api < 31) return TimeSettingStatus.NOT_CONFIRMED
                val (_, modeStatus) = checked("cmd time_zone_detector set_auto_detection_enabled $effectiveAutoZone")
                if (modeStatus != TimeSettingStatus.VERIFIED) return modeStatus
                // The service setter may normalize a missing raw setting to 0/1. Keep the baseline raw value.
                val (_, rawStatus) = checked(command)
                if (rawStatus != TimeSettingStatus.VERIFIED) return rawStatus
                if (readSetting(field).value != value ||
                    DeviceRepository(client).automaticTimeZoneEnabled(api) != effectiveAutoZone) {
                    return TimeSettingStatus.NOT_CONFIRMED
                }
            }
        }
        return TimeSettingStatus.VERIFIED
    }

    private fun readSetting(field: TimeSetting): Read {
        val command = if (field == TimeSetting.TIME_ZONE) "getprop persist.sys.timezone" else "settings get global ${key(field)}"
        val (result, status) = checked(command)
        if (status != TimeSettingStatus.VERIFIED) return Read(null, status)
        val raw = result!!.output.removeSuffix("\n").removeSuffix("\r")
        return if (validTimeSetting(field, raw)) Read(raw, TimeSettingStatus.VERIFIED)
            else Read(null, TimeSettingStatus.UNAVAILABLE)
    }

    private fun key(field: TimeSetting): String = when (field) {
        TimeSetting.NTP -> "ntp_server"
        TimeSetting.AUTO_TIME -> "auto_time"
        TimeSetting.AUTO_TIME_ZONE -> "auto_time_zone"
        TimeSetting.TIME_ZONE -> error("Not a Settings.Global key")
    }
}
