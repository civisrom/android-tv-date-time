package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AdbConnectSessionTest {
    @Test fun legacy_authorization_accepts_a_known_key_or_requests_confirmation_then_probes_shell() {
        // A deterministic peer covers both AUTH branches and old-protocol checksums.
        for (knownKey in listOf(true, false)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 5_000
                val peer = FutureTask {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = DataInputStream(socket.getInputStream())
                        fun read(): Packet {
                            val header = ByteArray(24).also(input::readFully)
                            val words = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            val command = words.int
                            val first = words.int
                            val second = words.int
                            val size = words.int
                            words.int // Modern clients may omit the checksum before negotiation.
                            assertEquals(command.inv(), words.int)
                            check(size in 0..4096)
                            return Packet(command, first, second, ByteArray(size).also(input::readFully))
                        }
                        fun write(command: Int, first: Int, second: Int, payload: ByteArray = byteArrayOf()) {
                            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(command).putInt(first).putInt(second).putInt(payload.size)
                                .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).array()
                            socket.getOutputStream().apply { write(header); write(payload); flush() }
                        }
                        assertEquals(CNXN, read().command)
                        val challenge = ByteArray(20) { it.toByte() }
                        write(AUTH, 1, 0, challenge)
                        var auth = read()
                        assertEquals(AUTH, auth.command)
                        assertEquals(2, auth.first)
                        assertTrue(auth.payload.size >= 256)
                        if (!knownKey) {
                            repeat(8) {
                                if (auth.first != 3) {
                                    write(AUTH, 1, 0, challenge)
                                    auth = read()
                                    assertEquals(AUTH, auth.command)
                                }
                            }
                            assertEquals(3, auth.first)
                            assertEquals(0.toByte(), auth.payload.last())
                        }
                        write(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
                        val opened = read()
                        assertEquals(OPEN, opened.command)
                        assertEquals("shell:$ADB_PROBE_COMMAND\u0000", opened.payload.toString(Charsets.UTF_8))
                        write(OKAY, 1, opened.first)
                        write(WRTE, 1, opened.first, "$ADB_PROBE_TOKEN\n".toByteArray())
                        assertEquals(OKAY, read().command)
                        write(CLSE, 1, opened.first)
                        assertEquals(CLSE, read().command)
                    }
                }
                Thread(peer, "legacy-adb-test").apply { isDaemon = true; start() }
                try {
                    KadbAdbClientFactory(2_000, 3_000).connect(DeviceAddress("127.0.0.1", listener.localPort)).use { client ->
                        assertFalse(client.shellV2Supported)
                    }
                    peer.get(5, TimeUnit.SECONDS)
                } finally { peer.cancel(true) }
            }
        }
    }

    private data class Packet(val command: Int, val first: Int, val second: Int, val payload: ByteArray)
    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}
