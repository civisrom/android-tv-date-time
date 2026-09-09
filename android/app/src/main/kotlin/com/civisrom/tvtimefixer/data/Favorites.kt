package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.device.DeviceInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class FavoriteDevice(val name: String, val address: DeviceAddress, val serial: String, val model: String) {
    /** Защита от случайной смены устройства за тем же IP; не криптографическая аутентификация. */
    fun matches(info: DeviceInfo?): Boolean = info != null && serial.isNotBlank() &&
        info.serial == serial && info.model == model
}

data class FavoriteNtp(val name: String, val server: String)
data class Favorites(val devices: List<FavoriteDevice> = emptyList(), val servers: List<FavoriteNtp> = emptyList())

fun usableDeviceSerial(serial: String): Boolean = serial.isNotBlank() && serial.length <= 256 &&
    serial.lowercase(java.util.Locale.ROOT) !in setOf("unknown", "null", "none", "0123456789abcdef") &&
    serial.none { it.isISOControl() }

/** Файл только в noBackupFilesDir. Здесь нет ни кода спаривания, ни ключей ADB. */
class FavoritesStore(private val file: File) {
    @Synchronized fun read(): Favorites {
        if (!file.exists()) return Favorites()
        require(file.length() <= 64 * 1024) { "Favorites file too large" }
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 1) { "Unknown favorites version" }
            val devices = List(input.readInt().also { require(it in 0..20) }) {
                FavoriteDevice(input.readUTF(), requireNotNull(parseDeviceAddress(input.readUTF())),
                    input.readUTF(), input.readUTF())
            }
            val servers = List(input.readInt().also { require(it in 0..30) }) {
                FavoriteNtp(input.readUTF(), input.readUTF())
            }
            require(input.read() == -1) { "Trailing favorites data" }
            Favorites(devices, servers).also(::validate)
        }
    }

    @Synchronized fun write(value: Favorites) {
        validate(value)
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Favorites directory") }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                val out = DataOutputStream(raw)
                out.writeInt(1)
                out.writeInt(value.devices.size)
                value.devices.forEach {
                    out.writeUTF(it.name); out.writeUTF(it.address.toString())
                    out.writeUTF(it.serial); out.writeUTF(it.model)
                }
                out.writeInt(value.servers.size)
                value.servers.forEach { out.writeUTF(it.name); out.writeUTF(it.server) }
                out.flush(); raw.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Favorites replacement failed")
        } finally {
            temporary.delete()
        }
    }

    private fun validate(value: Favorites) {
        fun validName(name: String) = name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }
        require(value.devices.size <= 20 && value.servers.size <= 30)
        require(value.devices.map { it.serial }.distinct().size == value.devices.size)
        require(value.servers.map { it.server.lowercase(java.util.Locale.ROOT) }.distinct().size == value.servers.size)
        value.devices.forEach {
            require(validName(it.name) && usableDeviceSerial(it.serial) && it.model.length <= 256)
            require(parseDeviceAddress(it.address.toString()) == it.address)
        }
        value.servers.forEach { require(validName(it.name) && isValidNtpServer(it.server)) }
    }
}
