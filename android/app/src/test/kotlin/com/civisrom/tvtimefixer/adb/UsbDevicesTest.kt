package com.civisrom.tvtimefixer.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class UsbDevicesTest {
    private val input = UsbEndpoint(2, 128, 512)
    private val output = UsbEndpoint(2, 0, 512)
    private val adb = UsbInterface(255, 66, 1, output, input)

    @Test fun `finds ADB after MTP with reversed bulk endpoints`() {
        val mtp = UsbInterface(6, 1, 1, input, output)
        val found = findAdbInterface(UsbDevice(mtp, adb))!!
        assertSame(adb, found.usbInterface)
        assertSame(input, found.input)
        assertSame(output, found.output)
    }

    @Test fun `rejects unrelated interfaces missing endpoints and interrupt endpoints`() {
        for (intf in listOf(UsbInterface(255, 66, 2, input, output),
            UsbInterface(255, 66, 1, input), UsbInterface(255, 66, 1, output),
            UsbInterface(255, 66, 1, input, UsbEndpoint(3, 0, 512)),
            UsbInterface(255, 66, 1, input, UsbEndpoint(2, 0, 0)))) {
            assertNull(findAdbInterface(UsbDevice(intf)))
        }
    }

    @Test fun `sends ZLP only for nonempty payload divisible by max packet size`() {
        val connection = UsbDeviceConnection()
        val io = AndroidUsbIo(connection, findAdbInterface(UsbDevice(adb))!!) { true }
        io.write(ByteArray(24), 1000)
        io.write(ByteArray(512), 1000)
        io.write(ByteArray(513), 1000)
        assertEquals(listOf(24, 512, 0, 513), connection.writes)
    }

    @Test fun `limits transfer chunks for older Android and sends final ZLP`() {
        val connection = UsbDeviceConnection()
        val io = AndroidUsbIo(connection, findAdbInterface(UsbDevice(adb))!!) { true }
        io.write(ByteArray(32768), 1000)
        assertEquals(listOf(16384, 16384, 0), connection.writes)
    }

    @Test fun `reads partial transfers without losing data`() {
        val connection = UsbDeviceConnection().apply { maxRead = 7 }
        val io = AndroidUsbIo(connection, findAdbInterface(UsbDevice(adb))!!) { true }
        assertArrayEquals(ByteArray(24) { it.toByte() }, io.read(24, 1000))
        assertEquals(4, connection.reads)
    }

    @Test fun `failed transfer and detached device are distinguished`() {
        val connection = UsbDeviceConnection().apply { fail = true }
        var attached = true
        val io = AndroidUsbIo(connection, findAdbInterface(UsbDevice(adb))!!) { attached }
        assertThrows(SocketTimeoutException::class.java) { io.read(24, 1000) }
        attached = false
        val failure = assertThrows(AdbConnectionException::class.java) { io.read(24, 1000) }
        assertEquals(ConnectionError.USB_DISCONNECTED, failure.reason)
    }

    @Test fun `close releases interface once and blocks further transfers`() {
        val connection = UsbDeviceConnection()
        val io = AndroidUsbIo(connection, findAdbInterface(UsbDevice(adb))!!) { true }
        io.close()
        io.close()
        assertEquals(1, connection.released)
        assertEquals(1, connection.closed)
        assertFalse(io.isOpen)
        assertThrows(AdbConnectionException::class.java) { io.write(ByteArray(24), 1000) }
        assertTrue(connection.writes.isEmpty())
    }
}
