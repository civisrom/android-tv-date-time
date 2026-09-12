package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.data.DeviceAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поддельное соединение: считает вызовы и умеет «умирать». */
private class FakeClient(var alive: Boolean = true) : AdbClient {
    var closed = false
    val commands = mutableListOf<String>()
    var response = ShellResult("tvtimefixer\n", "", 0)
    var shellError: Exception? = null
    var beforeShell: () -> Unit = {}

    override fun shell(command: String): ShellResult {
        commands += command
        beforeShell()
        shellError?.let { throw it }
        return response
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
    @Test fun `successful pairing waits for the device to accept its newly saved key`() = runBlocking {
        val client = FakeClient()
        var attempts = 0
        var failures = 0
        val factory = object : AdbClientFactory {
            override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
            override fun connect(address: DeviceAddress): AdbClient {
                if (++attempts == 1) throw AdbConnectionException(ConnectionError.TLS_FAILED)
                return client
            }
        }
        val connector = DeviceConnector(factory, onFailure = { _, _ -> failures++; null })
        assertEquals(ConnectionState.Connected(DeviceAddress("192.0.2.1", 40002)),
            connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002"))
        assertEquals(2, attempts)
        assertEquals(0, failures)
        assertSame(client, connector.activeClient)
    }

    @Test fun `post pairing retry is bounded and only applies to TLS rejection`() = runBlocking {
        for (reason in listOf(ConnectionError.TLS_FAILED, ConnectionError.CONNECTION_REFUSED)) {
            val factory = FakeFactory(failWith = reason)
            var failures = 0
            val connector = DeviceConnector(factory, onFailure = { _, _ -> failures++; 42L })
            val result = connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002")
            assertEquals(ConnectionState.Failed(DeviceAddress("192.0.2.1", 40002), reason, 42L), result)
            assertEquals(if (reason == ConnectionError.TLS_FAILED) 5 else 1, factory.connected.size)
            assertEquals(1, failures)
            assertEquals(1, factory.paired.size)
            assertNull(connector.activeClient)
        }
        val unpaired = FakeFactory(failWith = ConnectionError.TLS_FAILED)
        DeviceConnector(unpaired).connect("192.0.2.1:40002")
        assertEquals(1, unpaired.connected.size)

        var slowAttempts = 0
        val slow = object : AdbClientFactory {
            override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
            override fun connect(address: DeviceAddress): AdbClient {
                slowAttempts++
                Thread.sleep(5_100)
                throw AdbConnectionException(ConnectionError.TLS_FAILED)
            }
        }
        val result = DeviceConnector(slow).pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002")
        assertEquals(ConnectionError.TLS_FAILED, (result as ConnectionState.Failed).reason)
        assertEquals(1, slowAttempts)
    }

    @Test fun `cancelling post pairing key activation wait leaves no connection or retry`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val factory = object : AdbClientFactory {
            override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
            override fun connect(address: DeviceAddress): AdbClient {
                attempts++
                entered.complete(Unit)
                throw AdbConnectionException(ConnectionError.TLS_FAILED)
            }
        }
        val connector = DeviceConnector(factory)
        val operation = async(Dispatchers.IO) {
            connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002")
        }
        entered.await()
        operation.cancelAndJoin()
        assertEquals(1, attempts)
        assertEquals(ConnectionState.Disconnected, connector.state)
        assertNull(connector.activeClient)
    }

    @Test fun `cancellation as the paired connection opens closes the returned client`() = runBlocking {
        val client = FakeClient()
        lateinit var operation: Deferred<ConnectionState>
        val factory = object : AdbClientFactory {
            override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
            override fun connect(address: DeviceAddress): AdbClient {
                operation.cancel()
                return client
            }
        }
        val connector = DeviceConnector(factory)
        operation = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002")
        }
        operation.start()
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(ConnectionState.Disconnected, connector.state)
        assertNull(connector.activeClient)
        assertTrue(client.closed)
    }

    @Test fun `late post pairing TLS failure neither retries nor replaces a newer connection`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        val client = FakeClient()
        var oldAttempts = 0
        var failures = 0
        val factory = object : AdbClientFactory {
            override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
            override fun connect(address: DeviceAddress): AdbClient {
                if (address.host == "192.0.2.2") return client
                oldAttempts++
                entered.complete(Unit)
                check(proceed.await(3, TimeUnit.SECONDS))
                throw AdbConnectionException(ConnectionError.TLS_FAILED)
            }
        }
        val connector = DeviceConnector(factory, onFailure = { _, _ -> failures++; null })
        val operation = async(Dispatchers.IO) {
            connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002")
        }
        val expected = ConnectionState.Connected(DeviceAddress("192.0.2.2", 40003))
        try {
            entered.await()
            connector.disconnect()
            assertEquals(expected, connector.connect("192.0.2.2:40003"))
        } finally {
            proceed.countDown()
        }
        assertEquals(expected, operation.await())
        assertEquals(expected, connector.state)
        assertSame(client, connector.activeClient)
        assertEquals(1, oldAttempts)
        assertEquals(0, failures)
        assertFalse(client.closed)
    }

    @Test fun `invalid pairing input cannot leave an old transport hidden behind a failure`() = runBlocking {
        for ((address, code) in listOf("192.0.2.2" to "123456", "192.0.2.2:40001" to "12345")) {
            val factory = FakeFactory()
            val connector = DeviceConnector(factory)
            connector.connect("192.0.2.1")
            assertTrue(connector.pairAndConnect(address, code, "192.0.2.2:40002") is ConnectionState.Failed)
            assertNull(connector.activeClient)
            assertTrue(factory.clients.single().closed)
            assertTrue(factory.paired.isEmpty())
        }
    }

    @Test fun `pairing requires explicit ports for both endpoints`() = runBlocking {
        for ((pairing, connect) in listOf("192.0.2.1" to "192.0.2.1:40002", "192.0.2.1:40001" to "192.0.2.1")) {
            val factory = FakeFactory()
            val result = DeviceConnector(factory).pairAndConnect(pairing, "123456", connect)
            assertEquals(ConnectionError.INVALID_ADDRESS, (result as ConnectionState.Failed).reason)
            assertTrue(factory.paired.isEmpty())
            assertTrue(factory.connected.isEmpty())
        }
    }

    @Test fun `late pairing cannot reconnect or overwrite a newer connection after disconnect`() = runBlocking {
        for (replace in listOf(false, true)) for (reject in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val connected = mutableListOf<DeviceAddress>()
            val factory = object : AdbClientFactory {
                override fun connect(address: DeviceAddress): AdbClient {
                    connected += address
                    return FakeClient()
                }
                override suspend fun pair(address: DeviceAddress, pairingCode: String) {
                    entered.complete(Unit)
                    proceed.await()
                    if (reject) throw AdbConnectionException(ConnectionError.PAIRING_REJECTED)
                }
            }
            val connector = DeviceConnector(factory)
            val pairing = async { connector.pairAndConnect("192.0.2.1:40001", "123456", "192.0.2.1:40002") }
            entered.await()
            connector.disconnect()
            if (replace) connector.connect("192.0.2.2:40003")
            val expected = connector.state
            proceed.complete(Unit)
            assertEquals(expected, pairing.await())
            assertEquals(expected, connector.state)
            assertTrue(connected.none { it.host == "192.0.2.1" })
        }
    }

    @Test fun `failed pairing closes the previous transport instead of leaving it hidden`() = runBlocking {
        val factory = FakeFactory(pairFailWith = ConnectionError.PAIRING_REJECTED)
        val connector = DeviceConnector(factory)
        connector.connect("192.0.2.1")
        connector.pairAndConnect("192.0.2.2:40001", "123456", "192.0.2.2:40002")
        assertTrue(factory.clients.single().closed)
        assertNull(connector.activeClient)
    }

    @Test fun `открытый сокет без ответа устройства больше не считается подключением`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)
        connector.connect("192.168.1.20")
        val client = factory.clients.single()
        // После перехода с Wi-Fi на мобильный интернет локальный флаг ещё true.
        client.shellError = SocketTimeoutException("device left the network")
        assertTrue(client.isAlive())

        assertEquals(ConnectionState.Disconnected, connector.checkConnection())
        assertEquals(listOf("echo tvtimefixer"), client.commands)
        assertTrue(client.closed)
        assertNull(connector.activeClient)
        assertEquals(1, factory.connected.size)
    }

    @Test fun `проверка сохраняет отвечающее соединение без переподключения`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)
        val connected = connector.connect("192.168.1.20")
        assertEquals(connected, connector.checkConnection())
        assertEquals(listOf("echo tvtimefixer"), factory.clients.single().commands)
        assertFalse(factory.clients.single().closed)
        assertEquals(1, factory.connected.size)
    }

    @Test fun `пустой ответ неверный маркер и ненулевой код не подтверждают связь`() {
        for (response in listOf(ShellResult("", "", 0), ShellResult("other", "", 0), ShellResult("tvtimefixer", "", 1))) {
            val factory = FakeFactory()
            val connector = DeviceConnector(factory)
            connector.connect("192.168.1.20")
            factory.clients.single().response = response
            assertEquals(ConnectionState.Disconnected, connector.checkConnection())
            assertTrue(factory.clients.single().closed)
        }
    }

    @Test fun `проверка закрытого транспорта сбрасывает статус без shell команды`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)
        connector.connect("192.168.1.20")
        factory.clients.single().alive = false
        assertEquals(ConnectionState.Disconnected, connector.checkConnection())
        assertTrue(factory.clients.single().commands.isEmpty())
        assertTrue(factory.clients.single().closed)
    }

    @Test fun `проверка без подключения не открывает соединение`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)
        assertEquals(ConnectionState.Disconnected, connector.checkConnection())
        assertTrue(factory.connected.isEmpty())
    }

    @Test fun `отмена проверки не превращается в потерю связи`() {
        val factory = FakeFactory()
        val connector = DeviceConnector(factory)
        val connected = connector.connect("192.168.1.20")
        factory.clients.single().shellError = CancellationException()
        val error = runCatching { connector.checkConnection() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(connected, connector.state)
        assertFalse(factory.clients.single().closed)
    }

    @Test fun `запоздалая проверка не воскрешает отключённое и не сбрасывает новое соединение`() {
        for (replace in listOf(false, true)) for (failed in listOf(false, true)) {
            val factory = FakeFactory()
            val connector = DeviceConnector(factory)
            connector.connect("192.168.1.20")
            val oldClient = factory.clients.single()
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            oldClient.beforeShell = {
                entered.countDown()
                check(proceed.await(3, TimeUnit.SECONDS))
            }
            if (failed) oldClient.shellError = SocketTimeoutException()
            var result: ConnectionState? = null
            val worker = thread { result = connector.checkConnection() }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                connector.disconnect()
                if (replace) connector.connect("192.168.1.21")
            } finally {
                proceed.countDown()
                worker.join(3000)
            }
            assertFalse(worker.isAlive)
            val expected = if (replace) ConnectionState.Connected(DeviceAddress("192.168.1.21", 5555))
                else ConnectionState.Disconnected
            assertEquals(expected, result)
            assertEquals(expected, connector.state)
            if (replace) assertFalse(factory.clients.last().closed)
        }
    }

    @Test fun `diagnostic callback failure does not change connection result`() {
        val connector = DeviceConnector(FakeFactory(failWith = ConnectionError.NOT_AUTHORIZED),
            onFailure = { _, _ -> throw java.io.IOException("storage unavailable") })
        val result = connector.connect("192.168.1.20") as ConnectionState.Failed
        assertEquals(ConnectionError.NOT_AUTHORIZED, result.reason)
        assertNull(result.diagnosticId)
        assertNull(connector.activeClient)
    }

    @Test fun `diagnostic event remains attached to the failed connection`() {
        val connector = DeviceConnector(FakeFactory(failWith = ConnectionError.NOT_AUTHORIZED),
            onFailure = { _, _ -> 42L })
        assertEquals(42L, (connector.connect("192.168.1.20") as ConnectionState.Failed).diagnosticId)
    }


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
