package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.ui.AppState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

private class UsbFixtureClient : AdbClient {
    var closed = false
    override fun isAlive() = !closed
    override fun close() { closed = true }
    override fun shell(command: String) = ShellResult("ok", "", 0)
}

class UsbDeviceConnectorTest {
    private val usb = UsbDeviceAddress("/dev/bus/usb/001/002", "TV")
    private val networkClient = UsbFixtureClient()
    private val network = object : AdbClientFactory {
        override fun connect(address: DeviceAddress) = networkClient
        override suspend fun pair(address: DeviceAddress, pairingCode: String) = Unit
    }

    @Test fun `USB connection exposes device actions without a fabricated IP address`() {
        val client = UsbFixtureClient()
        val connector = DeviceConnector(network) { client }
        val result = connector.connectUsb(usb)
        val state = AppState(connection = result)
        assertTrue(state.connected)
        assertEquals(usb, state.connectedUsb)
        assertNull(state.connectedAddress)
        assertNull(result.addressOrNull())
        assertSame(client, connector.activeClient)
    }

    @Test fun `USB to network switch closes only the previous session`() {
        val client = UsbFixtureClient()
        val connector = DeviceConnector(network) { client }
        connector.connectUsb(usb)
        connector.connect("192.168.1.2")
        assertTrue(client.closed)
        assertFalse(networkClient.closed)
        assertNull(AppState(connection = connector.state).connectedUsb)
    }

    @Test fun `permission failure cannot publish a USB connection`() {
        val connector = DeviceConnector(network) { throw AdbConnectionException(ConnectionError.USB_PERMISSION_DENIED) }
        val state = AppState(connection = connector.connectUsb(usb))
        assertFalse(state.connected)
        assertNull(state.connectedUsb)
        assertNull(connector.activeClient)
    }

    @Test fun `disconnect during authorization closes late result without reviving session`() {
        lateResult(replaceWithNetwork = false)
    }

    @Test fun `late USB result cannot replace a newer network connection`() {
        lateResult(replaceWithNetwork = true)
    }

    private fun lateResult(replaceWithNetwork: Boolean) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val client = UsbFixtureClient()
        val connector = DeviceConnector(network) {
            entered.countDown()
            check(proceed.await(3, TimeUnit.SECONDS))
            client
        }
        val worker = thread { connector.connectUsb(usb) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        if (replaceWithNetwork) connector.connect("192.168.1.2") else connector.disconnect()
        proceed.countDown()
        worker.join(3000)
        assertFalse(worker.isAlive)
        assertTrue(client.closed)
        if (replaceWithNetwork) assertSame(networkClient, connector.activeClient)
        else assertEquals(ConnectionState.Disconnected, connector.state)
    }
}
