package com.civisrom.tvtimefixer.adb

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

private const val CNXN = 0x4e584e43
private const val AUTH = 0x48545541
private const val OPEN = 0x4e45504f
private const val OKAY = 0x59414b4f
private const val WRTE = 0x45545257
private const val CLSE = 0x45534c43

private fun frame(command: Int, arg0: Int = 0, arg1: Int = 0, data: ByteArray = byteArrayOf()): List<ByteArray> {
    val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv()).array()
    return if (data.isEmpty()) listOf(header) else listOf(header, data)
}

private fun shellFrame(id: Int, data: ByteArray): ByteArray =
    ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByte()).putInt(data.size).put(data).array()

private class FixtureIo : UsbPacketIo {
    val incoming = ArrayDeque<ByteArray>()
    val sent = mutableListOf<ByteArray>()
    var closes = 0
    override val isOpen: Boolean get() = closes == 0
    override fun close() { if (isOpen) closes++ }
    override fun read(size: Int, timeoutMs: Int): ByteArray {
        assertTrue(timeoutMs > 0)
        return incoming.pollFirst() ?: throw SocketTimeoutException("Fixture timeout")
    }
    override fun write(data: ByteArray, timeoutMs: Int) { sent += data.copyOf() }
    fun queue(command: Int, arg0: Int = 0, arg1: Int = 0, data: ByteArray = byteArrayOf()) {
        incoming.addAll(frame(command, arg0, arg1, data))
    }
    fun connection(v2: Boolean = true) {
        // Современный AOSP сообщает максимальную версию, но учитывает
        // нашу A_VERSION_MIN при формировании checksum.
        queue(CNXN, 0x01000001, 256 * 1024,
            (if (v2) "device::features=cmd,shell_v2;\u0000" else "device::\u0000").toByteArray())
    }
    fun shell(data: ByteArray, id: Int = 1, split: Int = data.size) {
        queue(OKAY, 55, id)
        data.asList().chunked(split.coerceAtLeast(1)).forEach {
            queue(WRTE, 55, id, it.toByteArray())
        }
        queue(CLSE, 55, id)
    }
}

private class FixtureIdentity : UsbAdbIdentity {
    var signed = 0
    var published = 0
    override fun sign(token: ByteArray): ByteArray {
        assertEquals(20, token.size)
        signed++
        return ByteArray(256) { 7 }
    }
    override fun publicKey(): ByteArray {
        published++
        return "fixture-public-key\u0000".toByteArray()
    }
}

class UsbAdbClientTest {
    @Test fun `AUTH signs once then publishes public key and waits for CNXN`() {
        val io = FixtureIo()
        io.queue(AUTH, 1, data = ByteArray(20))
        io.queue(AUTH, 1, data = ByteArray(20) { 1 })
        io.connection()
        val identity = FixtureIdentity()
        val client = UsbAdbClient(io, identity)
        client.connect()
        assertTrue(client.isAlive())
        assertEquals(1, identity.signed)
        assertEquals(1, identity.published)
        assertEquals(24, io.sent[0].size)
        assertEquals("host::features=shell_v2", io.sent[1].toString(Charsets.UTF_8))
    }

    @Test fun `CNXN for an already authorized host does not send new keys`() {
        val io = FixtureIo().apply { connection() }
        val identity = FixtureIdentity()
        UsbAdbClient(io, identity).connect()
        assertEquals(0, identity.signed)
        assertEquals(0, identity.published)
    }

    @Test fun `authorization timeout closes transport and reports RSA confirmation`() {
        val io = FixtureIo().apply {
            queue(AUTH, 1, data = ByteArray(20))
            queue(AUTH, 1, data = ByteArray(20))
        }
        val client = UsbAdbClient(io, FixtureIdentity())
        val error = assertThrows(AdbConnectionException::class.java) { client.connect() }
        assertEquals(ConnectionError.NOT_AUTHORIZED, error.reason)
        assertFalse(client.isAlive())
        assertEquals(1, io.closes)
    }

    @Test fun `malformed AUTH is rejected before signing`() {
        val io = FixtureIo().apply { queue(AUTH, 1, data = ByteArray(21)) }
        val identity = FixtureIdentity()
        assertThrows(IOException::class.java) { UsbAdbClient(io, identity).connect() }
        assertEquals(0, identity.signed)
        assertFalse(io.isOpen)
    }

    @Test fun `shell v2 preserves stderr and nonzero exit across fragmented WRTE`() {
        val wire = shellFrame(1, "ответ\n".toByteArray()) +
            shellFrame(2, "denied\n".toByteArray()) + shellFrame(3, byteArrayOf(13))
        val io = FixtureIo().apply { connection(); shell(wire, split = 3) }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        val result = client.shell("getprop")
        assertEquals("ответ\n", result.output)
        assertEquals("denied\n", result.errorOutput)
        assertEquals(13, result.exitCode)
        assertTrue(client.isAlive())
        // CNXN payload тоже может иметь длину 24: границы задаёт заголовок,
        // а не размер отдельного transfer.
        val headers = buildList {
            var index = 0
            while (index < io.sent.size) {
                val header = ByteBuffer.wrap(io.sent[index++]).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(24, header.remaining())
                add(header.int)
                val length = header.getInt(12)
                if (length > 0) assertEquals(length, io.sent[index++].size)
            }
        }
        assertEquals(OPEN, headers[1])
        assertEquals(CLSE, headers.last())
        assertEquals((wire.size + 2) / 3, headers.count { it == OKAY })
    }

    @Test fun `legacy shell and consecutive streams reuse USB connection`() {
        val io = FixtureIo().apply {
            connection(v2 = false)
            shell("one\n".toByteArray())
            shell("two\n".toByteArray(), id = 2)
        }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        assertEquals("one\n", client.shell("echo one").output)
        assertEquals("two\n", client.shell("echo two").output)
        assertTrue(io.sent.any { it.toString(Charsets.UTF_8) == "shell:echo two\u0000" })
    }

    @Test fun `older adbd may close an established stream with zero remote ID`() {
        val io = FixtureIo().apply {
            connection(v2 = false)
            queue(OKAY, 55, 1)
            queue(WRTE, 55, 1, "ok\n".toByteArray())
            queue(CLSE, 0, 1)
        }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        assertEquals("ok\n", client.shell("echo ok").output)
        assertTrue(client.isAlive())
    }

    @Test fun `header magic length checksum and truncation are validated before allocation`() {
        val valid = frame(CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
        for (position in listOf(12, 16, 20)) {
            val io = FixtureIo()
            val corrupt = valid[0].copyOf()
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(position, -1)
            io.incoming.add(corrupt)
            if (position == 16) io.incoming.add(valid[1])
            assertThrows(IOException::class.java) { UsbAdbClient(io, FixtureIdentity()).connect() }
            assertFalse(io.isOpen)
        }
        val io = FixtureIo().apply { incoming.add(ByteArray(12)) }
        assertThrows(IOException::class.java) { UsbAdbClient(io, FixtureIdentity()).connect() }
    }

    @Test fun `wrong stream ID invalidates the session`() {
        val io = FixtureIo().apply {
            connection()
            queue(OKAY, 55, 1)
            queue(WRTE, 66, 1, byteArrayOf(1))
        }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        assertThrows(IOException::class.java) { client.shell("id") }
        assertFalse(client.isAlive())
    }

    @Test fun `rejected shell service never looks successful`() {
        val io = FixtureIo().apply { connection(); queue(CLSE, 0, 1) }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        assertThrows(IOException::class.java) { client.shell("id") }
    }

    @Test fun `shell command obeys peer payload limit`() {
        val io = FixtureIo().apply { queue(CNXN, 0x01000000, 32, "device::\u0000".toByteArray()) }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        assertThrows(IllegalArgumentException::class.java) { client.shell("x".repeat(33)) }
        assertEquals(2, io.sent.size)
    }

    @Test fun `closed USB cannot send commands and close is idempotent`() {
        val io = FixtureIo().apply { connection() }
        val client = UsbAdbClient(io, FixtureIdentity())
        client.connect()
        client.close()
        client.close()
        assertEquals(1, io.closes)
        assertThrows(AdbConnectionException::class.java) { client.shell("reboot") }
        assertEquals(2, io.sent.size)
    }

    @Test fun `shell v2 rejects missing exit invalid length and data after exit`() {
        for (data in listOf(byteArrayOf(1), shellFrame(1, "no exit".toByteArray()),
            shellFrame(3, byteArrayOf()), shellFrame(3, byteArrayOf(0)) + shellFrame(1, byteArrayOf()),
            shellFrame(0, byteArrayOf()), byteArrayOf(1, -1, -1, -1, 127))) {
            assertThrows(IOException::class.java) { parseShellV2(data) }
        }
    }
}
