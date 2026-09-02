package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.data.DeviceAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поддельное соединение: считает вызовы и умеет «умирать». */
private class FakeClient(var alive: Boolean = true) : AdbClient {
    var closed = false
    val commands = mutableListOf<String>()

    override fun shell(command: String): ShellResult {
        commands += command
        return ShellResult(output = "ok", errorOutput = "", exitCode = 0)
    }

    override fun isAlive(): Boolean = alive && !closed

    override fun close() {
        closed = true
    }
}

private class FakeFactory(
    private val failWith: ConnectionError? = null,
    private val pairFailWith: ConnectionError? = null,
) : AdbClientFactory {
    val connected = mutableListOf<DeviceAddress>()
    val paired = mutableListOf<Pair<DeviceAddress, String>>()
    val clients = mutableListOf<FakeClient>()

    override fun connect(address: DeviceAddress): AdbClient {
        connected += address
        failWith?.let { throw AdbConnectionException(it) }
        return FakeClient().also { clients += it }
    }

    override suspend fun pair(address: DeviceAddress, pairingCode: String) {
        paired += address to pairingCode
        pairFailWith?.let { throw AdbConnectionException(it) }
    }
}

class DeviceConnectorTest {

    @Test
    fun `успешное подключение по адресу с портом`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        val state = connector.connect("192.168.1.20:37105")

        assertEquals(ConnectionState.Connected(DeviceAddress("192.168.1.20", 37105)), state)
        assertEquals(listOf(DeviceAddress("192.168.1.20", 37105)), factory.connected)
    }

    @Test
    fun `некорректный адрес не доходит до сети`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        val state = connector.connect("не адрес")

        assertEquals(ConnectionState.Failed(null, ConnectionError.INVALID_ADDRESS), state)
        assertTrue("до фабрики дойти не должно", factory.connected.isEmpty())
    }

    @Test
    fun `недостижимое устройство даёт понятную причину`() {
        val connector = DeviceConnector(FakeFactory(failWith = ConnectionError.UNREACHABLE))

        val state = connector.connect("192.168.1.20")

        assertEquals(
            ConnectionState.Failed(DeviceAddress("192.168.1.20", 5555), ConnectionError.UNREACHABLE),
            state,
        )
        assertNull(connector.activeClient)
    }

    @Test
    fun `живое соединение к тому же адресу переиспользуется`() {
        // Второе подключение к тому же adbd конфликтует с первым
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        connector.connect("192.168.1.20:5555")
        val first = connector.activeClient
        connector.connect("192.168.1.20:5555")

        assertEquals(1, factory.connected.size)
        assertSame(first, connector.activeClient)
    }

    @Test
    fun `умершее соединение переоткрывается`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        connector.connect("192.168.1.20")
        factory.clients.first().alive = false
        connector.connect("192.168.1.20")

        assertEquals(2, factory.connected.size)
    }

    @Test
    fun `смена адреса закрывает прежнее соединение`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        connector.connect("192.168.1.20")
        connector.connect("192.168.1.21")

        assertTrue("прежнее соединение должно быть закрыто", factory.clients.first().closed)
        assertEquals(2, factory.connected.size)
    }

    @Test
    fun `спаривание использует порт спаривания, а подключение — свой`() = runBlocking {
        // Это разные порты на устройстве, и выводить один из другого нельзя
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        val state = connector.pairAndConnect(
            pairingInput = "192.168.1.20:41234",
            pairingCode = "123456",
            connectInput = "192.168.1.20:37105",
        )

        assertEquals(listOf(DeviceAddress("192.168.1.20", 41234) to "123456"), factory.paired)
        assertEquals(listOf(DeviceAddress("192.168.1.20", 37105)), factory.connected)
        assertEquals(ConnectionState.Connected(DeviceAddress("192.168.1.20", 37105)), state)
    }

    @Test
    fun `неверный код спаривания не доходит до устройства`() = runBlocking {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        val state = connector.pairAndConnect("192.168.1.20:41234", "12345", "192.168.1.20:37105")

        assertEquals(ConnectionError.PAIRING_REJECTED, (state as ConnectionState.Failed).reason)
        assertTrue(factory.paired.isEmpty())
    }

    @Test
    fun `отказ спаривания не приводит к попытке подключения`() = runBlocking {
        val factory = FakeFactory(pairFailWith = ConnectionError.PAIRING_REJECTED)
        val connector = DeviceConnector(factory)

        val state = connector.pairAndConnect("192.168.1.20:41234", "123456", "192.168.1.20:37105")

        assertEquals(ConnectionError.PAIRING_REJECTED, (state as ConnectionState.Failed).reason)
        assertTrue("подключаться после неудачного спаривания незачем", factory.connected.isEmpty())
    }

    @Test
    fun `режим телевизора пробует loopback`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        connector.connectLoopback()

        assertEquals(listOf(LOOPBACK_ADDRESS), factory.connected)
        assertEquals("127.0.0.1", LOOPBACK_ADDRESS.host)
    }

    @Test
    fun `отказ loopback — обычная ошибка, а не авария`() {
        // Не всякая прошивка принимает adb-соединение с самой себя
        val connector = DeviceConnector(FakeFactory(failWith = ConnectionError.UNREACHABLE))

        val state = connector.connectLoopback()

        assertTrue(state is ConnectionState.Failed)
        assertEquals(ConnectionError.UNREACHABLE, (state as ConnectionState.Failed).reason)
    }

    @Test
    fun `подсказка адреса зависит от режима`() {
        val connector = DeviceConnector(FakeFactory())

        assertEquals(LOOPBACK_ADDRESS, connector.suggestedAddress(DeviceMode.TELEVISION, null))
        assertEquals(
            DeviceAddress("192.168.1.20", 5555),
            connector.suggestedAddress(DeviceMode.HANDHELD, "192.168.1.20"),
        )
        assertNull(connector.suggestedAddress(DeviceMode.HANDHELD, null))
        assertNull(connector.suggestedAddress(DeviceMode.HANDHELD, "мусор"))
    }

    @Test
    fun `disconnect закрывает соединение и сбрасывает состояние`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)

        connector.connect("192.168.1.20")
        connector.disconnect()

        assertTrue(factory.clients.first().closed)
        assertEquals(ConnectionState.Disconnected, connector.state)
        assertNull(connector.activeClient)
    }
}
