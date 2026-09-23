package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.device.DeviceIdentityKind
import com.civisrom.tvtimefixer.device.TimeDeviceIdentity
import com.civisrom.tvtimefixer.device.TimeSetting
import com.civisrom.tvtimefixer.device.TimeSettings
import com.civisrom.tvtimefixer.device.validTimeSetting
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class SavedTimeSettings(val identity: TimeDeviceIdentity, val capturedAt: Long, val settings: TimeSettings)
data class TimeProfile(val name: String, val saved: SavedTimeSettings)
data class SavedTimeDevices(val snapshots: List<SavedTimeSettings> = emptyList(), val profiles: List<TimeProfile> = emptyList())

/** Local no-backup storage: only identity hashes and time settings, never addresses or ADB trust material. */
class TimeSettingsStore(private val file: File) {
    @Synchronized fun read(): SavedTimeDevices {
        if (!file.exists()) return SavedTimeDevices()
        require(file.length() <= 512 * 1024) { "Time settings file too large" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 1) { "Unknown time settings version" }
            val snapshots = List(input.readInt().also { require(it in 0..32) }) { readSaved(input) }
            val profiles = List(input.readInt().also { require(it in 0..64) }) { TimeProfile(input.readUTF(), readSaved(input)) }
            require(input.read() == -1) { "Trailing time settings data" }
            SavedTimeDevices(snapshots, profiles).also(::validate)
        }
    }

    /** The first baseline survives later changes and app restarts. Replacement is an explicit UI action. */
    @Synchronized fun saveSnapshot(snapshot: SavedTimeSettings, replace: Boolean = false): SavedTimeSettings {
        val saved = read()
        val existing = saved.snapshots.firstOrNull { it.identity == snapshot.identity }
        if (existing != null && !replace) return existing
        write(saved.copy(snapshots = saved.snapshots.filterNot { it.identity == snapshot.identity } + snapshot))
        return snapshot
    }

    @Synchronized fun saveProfile(profile: TimeProfile, replace: Boolean = false) {
        val saved = read()
        fun TimeProfile.same() = name == profile.name && this.saved.identity == profile.saved.identity
        require(replace || saved.profiles.none { it.same() }) { "Profile already exists" }
        write(saved.copy(profiles = saved.profiles.filterNot { it.same() } + profile))
    }

    @Synchronized fun removeProfile(identity: TimeDeviceIdentity, name: String) {
        val saved = read()
        write(saved.copy(profiles = saved.profiles.filterNot { it.saved.identity == identity && it.name == name }))
    }

    private fun readSaved(input: DataInputStream): SavedTimeSettings {
        val identity = TimeDeviceIdentity(input.readUTF(), DeviceIdentityKind.valueOf(input.readUTF()))
        val captured = input.readLong()
        val values = TimeSetting.entries.associateWith { input.readUTF() }
        val autoZone = when (input.readByte().toInt()) { 0 -> false; 1 -> true; 2 -> null; else -> error("Invalid auto zone") }
        return SavedTimeSettings(identity, captured, TimeSettings(values, autoZone))
    }

    private fun writeSaved(out: DataOutputStream, saved: SavedTimeSettings) {
        out.writeUTF(saved.identity.digest); out.writeUTF(saved.identity.kind.name); out.writeLong(saved.capturedAt)
        TimeSetting.entries.forEach { out.writeUTF(saved.settings.values.getValue(it)) }
        out.writeByte(when (saved.settings.effectiveAutoZone) { false -> 0; true -> 1; null -> 2 })
    }

    private fun write(value: SavedTimeDevices) {
        validate(value)
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Time settings directory") }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                val out = DataOutputStream(raw)
                out.writeInt(1); out.writeInt(value.snapshots.size)
                value.snapshots.forEach { writeSaved(out, it) }
                out.writeInt(value.profiles.size)
                value.profiles.forEach { out.writeUTF(it.name); writeSaved(out, it.saved) }
                out.flush(); raw.fd.sync()
            }
            require(temporary.length() <= 512 * 1024) { "Time settings file too large" }
            if (!temporary.renameTo(file)) throw IOException("Time settings replacement failed")
        } finally { temporary.delete() }
    }

    private fun validate(value: SavedTimeDevices) {
        require(value.snapshots.size <= 32 && value.profiles.size <= 64)
        require(value.snapshots.map { it.identity }.distinct().size == value.snapshots.size)
        require(value.profiles.map { it.saved.identity to it.name }.distinct().size == value.profiles.size)
        value.profiles.forEach { require(it.name.isNotBlank() && it.name.length <= 80 && it.name.none(Char::isISOControl)) }
        (value.snapshots + value.profiles.map { it.saved }).forEach { saved ->
            require(saved.identity.digest.matches(Regex("[0-9a-f]{64}")) && saved.capturedAt >= 0)
            require(saved.settings.complete && saved.settings.values.size == TimeSetting.entries.size)
            require(saved.settings.values.all { (field, value) -> validTimeSetting(field, value) })
        }
    }
}
