package com.civisrom.tvtimefixer.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Эфемерный адрес USB; serialNumber до разрешения Android читать запрещает. */
data class UsbDeviceAddress(val deviceName: String, val label: String) : DeviceTarget {
    override fun toString(): String = "USB: $label"
}

data class UsbDeviceListing(val devices: List<UsbDeviceAddress>, val attachedCount: Int)

internal data class AdbUsbInterface(
    val usbInterface: UsbInterface,
    val input: UsbEndpoint,
    val output: UsbEndpoint,
)

internal fun findAdbInterface(device: UsbDevice): AdbUsbInterface? {
    for (index in 0 until device.interfaceCount) {
        val intf = device.getInterface(index)
        if (intf.interfaceClass != 0xff || intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 1) continue
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (endpointIndex in 0 until intf.endpointCount) {
            val endpoint = intf.getEndpoint(endpointIndex)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK || endpoint.maxPacketSize <= 0) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> input = endpoint
                UsbConstants.USB_DIR_OUT -> output = endpoint
            }
        }
        if (input != null && output != null) return AdbUsbInterface(intf, input, output)
    }
    return null
}

/** Владеет только USB-интерфейсом ADB, не меняет режим USB/TCP на телевизоре. */
class UsbDevices(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    val supported: Boolean = manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    private val lock = Any()
    private var closed = false
    private var active: Pair<String, AndroidUsbIo>? = null

    fun scan(): UsbDeviceListing {
        if (!supported) return UsbDeviceListing(emptyList(), 0)
        val attached = manager!!.deviceList.values.toList()
        val adbDevices = attached.filter { findAdbInterface(it) != null }.map { device ->
            UsbDeviceAddress(device.deviceName,
                device.productName?.takeIf { it.isNotBlank() }
                    ?: "%04x:%04x".format(device.vendorId, device.productId))
        }.sortedBy { it.deviceName }
        return UsbDeviceListing(adbDevices, attached.size)
    }

    fun list(): List<UsbDeviceAddress> = scan().devices

    suspend fun requestPermission(address: UsbDeviceAddress): Boolean {
        val manager = manager ?: return false
        val device = manager.deviceList[address.deviceName] ?: return false
        if (manager.hasPermission(device)) return true
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { continuation ->
                val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
                val pending = PendingIntent.getBroadcast(context, 0,
                    Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                var registered = false
                val finished = AtomicBoolean(false)
                lateinit var receiver: BroadcastReceiver
                fun cleanup() {
                    pending.cancel()
                    if (registered) {
                        runCatching { context.unregisterReceiver(receiver) }
                        registered = false
                    }
                }
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != action || !finished.compareAndSet(false, true)) return
                        cleanup()
                        // Не доверяем extras: immutable PendingIntent не обязан
                        // принимать fill-in extras. Авторитетен UsbManager.
                        val current = manager.deviceList[address.deviceName]
                        val granted = current != null && manager.hasPermission(current)
                        if (continuation.isActive) continuation.resume(granted)
                    }
                }
                try {
                    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    continuation.invokeOnCancellation {
                        if (finished.compareAndSet(false, true)) cleanup()
                    }
                    if (continuation.isActive) manager.requestPermission(device, pending)
                } catch (_: Exception) {
                    if (finished.compareAndSet(false, true)) {
                        cleanup()
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        } ?: false
    }

    /** Вызывается только на IO-потоке; успех включает CNXN/AUTH и shell-пробу. */
    fun connect(address: UsbDeviceAddress): AdbClient {
        if (!supported) throw AdbConnectionException(ConnectionError.USB_UNSUPPORTED)
        val manager = manager ?: throw AdbConnectionException(ConnectionError.USB_UNSUPPORTED)
        val device = manager.deviceList[address.deviceName]
            ?: throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
        if (!manager.hasPermission(device)) throw AdbConnectionException(ConnectionError.USB_PERMISSION_DENIED)
        val intf = findAdbInterface(device) ?: throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
        val connection = try {
            manager.openDevice(device) ?: throw AdbConnectionException(ConnectionError.USB_BUSY)
        } catch (e: SecurityException) {
            throw AdbConnectionException(ConnectionError.USB_PERMISSION_DENIED, e)
        }
        val io = AndroidUsbIo(connection, intf) { manager.deviceList.containsKey(address.deviceName) }
        try {
            if (!connection.claimInterface(intf.usbInterface, false)) {
                throw AdbConnectionException(ConnectionError.USB_BUSY)
            }
            synchronized(lock) {
                if (closed) throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
                active = address.deviceName to io
            }
            val client = UsbAdbClient(io, KadbUsbIdentity())
            client.connect()
            val probe = client.shell("echo tvtimefixer")
            if (probe.exitCode != 0 || probe.trimmedOutput != "tvtimefixer" || !io.isOpen) {
                throw AdbConnectionException(ConnectionError.USB_IO)
            }
            return client
        } catch (e: Exception) {
            io.close()
            if (e is AdbConnectionException) throw e
            throw AdbConnectionException(ConnectionError.USB_IO, e)
        }
    }

    fun detached(deviceName: String) {
        synchronized(lock) {
            active?.takeIf { it.first == deviceName }?.let {
                it.second.close()
                active = null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active?.second?.close()
            active = null
        }
    }
}

internal class AndroidUsbIo(
    private val connection: UsbDeviceConnection,
    private val intf: AdbUsbInterface,
    private val attached: () -> Boolean,
) : UsbPacketIo {
    private val closed = AtomicBoolean(false)
    override val isOpen: Boolean get() = !closed.get() && attached()

    override fun read(size: Int, timeoutMs: Int): ByteArray {
        val data = ByteArray(size)
        transfer(data, intf.input, timeoutMs, reading = true)
        return data
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        transfer(data, intf.output, timeoutMs, reading = false)
    }

    private fun transfer(data: ByteArray, endpoint: UsbEndpoint, timeoutMs: Int, reading: Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        fun remaining(): Int {
            if (!isOpen) throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("USB transfer timed out")
            return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        var offset = 0
        while (offset < data.size) {
            val requested = minOf(data.size - offset, 16_384)
            val count = connection.bulkTransfer(endpoint, data, offset,
                requested, minOf(remaining(), 1000))
            if (count < 0) {
                if (!isOpen) throw AdbConnectionException(ConnectionError.USB_DISCONNECTED)
                if (reading) {
                    // Короткие read-ожидания позволяют завершить Activity и
                    // закрыть интерфейс во время минутного ожидания RSA.
                    remaining()
                    Thread.sleep(10)
                    continue
                }
                throw SocketTimeoutException("USB transfer failed or timed out")
            }
            if (count > requested) throw IOException("Invalid USB transfer length")
            if (!reading && count != requested) throw IOException("Short USB write")
            offset += count // ZLP на чтении допустим; общий deadline ограничивает ожидание.
        }
        // AOSP посылает ZLP после payload, кратного OUT maxPacketSize.
        // Заголовок ADB имеет 24 байта и у bulk ADB не кратен maxPacketSize.
        if (!reading && data.isNotEmpty() && data.size % endpoint.maxPacketSize == 0) {
            if (connection.bulkTransfer(endpoint, byteArrayOf(), 0, 0, remaining()) != 0) {
                throw IOException("USB zero-length packet failed")
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.releaseInterface(intf.usbInterface)
            } finally {
                connection.close()
            }
        }
    }
}
