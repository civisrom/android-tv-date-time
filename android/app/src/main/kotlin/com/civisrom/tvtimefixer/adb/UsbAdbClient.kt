package com.civisrom.tvtimefixer.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Отдельные USB transfers для заголовка и данных, как в AOSP LibUsbDevice. */
internal interface UsbPacketIo : AutoCloseable {
    val isOpen: Boolean
    fun read(size: Int, timeoutMs: Int): ByteArray
    fun write(data: ByteArray, timeoutMs: Int)
}

internal interface UsbAdbIdentity {
    fun sign(token: ByteArray): ByteArray
    fun publicKey(): ByteArray
}

/**
 * ADB поверх USB host. Один shell-поток за раз, без фоновых читателей.
 * CNXN объявляет 4096 байт и только shell_v2: никаких delayed ACK/TLS.
 * Это сохраняет совместимость с Android 6 и ограничением USB до Android 9.
 */
internal class UsbAdbClient(
    private val io: UsbPacketIo,
    private val identity: UsbAdbIdentity,
    private val commandTimeoutMs: Int = 15_000,
) : AdbClient {
    @Volatile private var connected = false
    private var peerMax = MAX_PAYLOAD
    private var shellV2 = false
    private var nextId = 1

    fun connect(authTimeoutMs: Int = 60_000) {
        val deadline = deadline(authTimeoutMs)
        var signed = false
        var publicKeySent = false
        try {
            // AOSP parse_banner работает со std::string: NUL в конце стал бы
            // частью имени последней feature, в отличие от service OPEN.
            send(CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2".toByteArray(), deadline)
            while (true) {
                val packet = read(deadline)
                when (packet.command) {
                    CNXN -> {
                        if (packet.arg0 < VERSION || packet.arg1 <= 0) fail("Invalid ADB CNXN")
                        peerMax = minOf(packet.arg1, MAX_PAYLOAD)
                        val banner = packet.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                        shellV2 = banner.substringAfter("::", "").split(';').any {
                            it.startsWith("features=") && "shell_v2" in it.removePrefix("features=").split(',')
                        }
                        connected = true
                        return
                    }
                    AUTH -> {
                        if (packet.arg0 != 1 || packet.data.size != 20) fail("Invalid ADB AUTH token")
                        when {
                            !signed -> {
                                send(AUTH, 2, 0, identity.sign(packet.data), deadline)
                                signed = true
                            }
                            !publicKeySent -> {
                                send(AUTH, 3, 0, identity.publicKey(), deadline)
                                publicKeySent = true
                            }
                            else -> throw AdbConnectionException(ConnectionError.NOT_AUTHORIZED)
                        }
                    }
                    else -> fail("Unexpected ADB handshake packet")
                }
            }
        } catch (e: SocketTimeoutException) {
            close()
            throw AdbConnectionException(
                if (publicKeySent) ConnectionError.NOT_AUTHORIZED else ConnectionError.USB_IO,
                e,
            )
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized
    override fun shell(command: String): ShellResult {
        if (!isAlive()) throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
        require('\u0000' !in command) { "NUL in shell command" }
        val destination = ((if (shellV2) "shell,v2,raw:" else "shell:") + command + '\u0000').toByteArray()
        require(destination.size <= peerMax) { "USB shell command is too long" }
        val deadline = deadline(commandTimeoutMs)
        val localId = nextId++
        val output = ByteArrayOutputStream()
        try {
            send(OPEN, localId, 0, destination, deadline)
            val opened = read(deadline)
            if (opened.command != OKAY || opened.arg1 != localId || opened.arg0 == 0) {
                fail("ADB shell service rejected")
            }
            val remoteId = opened.arg0
            while (true) {
                val packet = read(deadline)
                // Старые adbd отправляли CLSE(0, id) и при нормальном закрытии;
                // AOSP handle_packet сохраняет эту совместимость. Поток здесь один.
                val legacyClose = packet.command == CLSE && packet.arg0 == 0
                if ((!legacyClose && packet.arg0 != remoteId) || packet.arg1 != localId) {
                    fail("Wrong ADB stream ID")
                }
                when (packet.command) {
                    WRTE -> {
                        if (output.size() + packet.data.size > MAX_OUTPUT) fail("USB shell output is too large")
                        output.write(packet.data)
                        send(OKAY, localId, remoteId, byteArrayOf(), deadline)
                    }
                    CLSE -> {
                        send(CLSE, localId, remoteId, byteArrayOf(), deadline)
                        return if (shellV2) parseShellV2(output.toByteArray())
                        else ShellResult(output.toString("UTF-8"), "", 0)
                    }
                    else -> fail("Unexpected ADB shell packet")
                }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun isAlive(): Boolean = connected && io.isOpen

    // Не захватывает монитор shell(): detach/onDestroy должны прервать read.
    override fun close() {
        connected = false
        io.close()
    }

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(deadline: Long): Packet {
        val header = io.read(24, remaining(deadline))
        if (header.size != 24) fail("Truncated ADB header")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        if (magic != command.inv() || length !in 0..MAX_PAYLOAD) fail("Invalid ADB header")
        val data = if (length == 0) byteArrayOf() else io.read(length, remaining(deadline))
        // Мы объявили A_VERSION_MIN: обе стороны обязаны передавать checksum.
        if (data.size != length || checksum != checksum(data)) fail("Invalid ADB payload")
        return Packet(command, arg0, arg1, data)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray, deadline: Long) {
        if (data.size > peerMax) fail("ADB payload exceeds peer limit")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(checksum(data)).putInt(command.inv()).array()
        io.write(header, remaining(deadline))
        if (data.isNotEmpty()) io.write(data, remaining(deadline))
    }

    private fun deadline(timeoutMs: Int) = System.nanoTime() + timeoutMs * 1_000_000L

    private fun remaining(deadline: Long): Int {
        val left = (deadline - System.nanoTime()) / 1_000_000
        if (left <= 0) throw SocketTimeoutException("USB ADB operation timed out")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xff }

    private fun fail(message: String): Nothing = throw IOException(message)

    private companion object {
        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 4096
        const val MAX_OUTPUT = 4 * 1024 * 1024
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}

/** Пакеты shell_v2 могут разрываться на границах ADB WRTE. */
internal fun parseShellV2(data: ByteArray): ShellResult {
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    var exitCode: Int? = null
    while (buffer.hasRemaining()) {
        if (buffer.remaining() < 5 || exitCode != null) throw IOException("Invalid shell_v2 frame")
        val id = buffer.get().toInt() and 0xff
        val length = buffer.int
        if (length < 0 || length > buffer.remaining()) throw IOException("Invalid shell_v2 length")
        val payload = ByteArray(length).also { buffer.get(it) }
        when (id) {
            1 -> stdout.write(payload)
            2 -> stderr.write(payload)
            3 -> {
                if (length != 1) throw IOException("Invalid shell_v2 exit code")
                exitCode = payload[0].toInt() and 0xff
            }
            else -> throw IOException("Unexpected shell_v2 channel")
        }
    }
    return ShellResult(stdout.toString("UTF-8"), stderr.toString("UTF-8"),
        exitCode ?: throw IOException("Missing shell_v2 exit code"))
}
