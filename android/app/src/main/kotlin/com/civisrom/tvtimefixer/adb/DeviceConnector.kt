package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.DEFAULT_ADB_PORT
import com.civisrom.tvtimefixer.data.isValidPairingCode
import com.civisrom.tvtimefixer.data.parseDeviceAddress
import kotlinx.coroutines.CancellationException

/** Состояние подключения к устройству. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: DeviceTarget) : ConnectionState
    data class Checking(val address: DeviceTarget) : ConnectionState
    data class Connected(val address: DeviceTarget) : ConnectionState
    data class Failed(val address: DeviceTarget?, val reason: ConnectionError, val diagnosticId: Long? = null) : ConnectionState
}

/** Адрес самого устройства, на котором запущено приложение (режим телевизора). */
val LOOPBACK_ADDRESS = DeviceAddress("127.0.0.1", DEFAULT_ADB_PORT)

/**
 * Управляет подключением: разбирает адрес, открывает соединение, помнит его.
 *
 * Вся работа с сетью спрятана за [AdbClientFactory], поэтому эта логика
 * целиком проверяется на JVM — без телевизора и без эмулятора.
 */
class DeviceConnector(
    private val factory: AdbClientFactory,
    private val onFailure: (DeviceTarget?, AdbConnectionException) -> Long? = { _, _ -> null },
    private val usbConnect: (UsbDeviceAddress) -> AdbClient = {
        throw AdbConnectionException(ConnectionError.USB_UNSUPPORTED)
    },
) {

    @Volatile
    var state: ConnectionState = ConnectionState.Disconnected
        private set

    private val lock = Any()
    private var generation = 0
    @Volatile
    private var client: AdbClient? = null

    /** Текущее соединение, если транспорт ещё не сообщил о закрытии. */
    val activeClient: AdbClient?
        get() = client?.takeIf { it.isAlive() }

    /** Проверяет ответ устройства: открытый локальный сокет переживает потерю Wi-Fi. Вызывать вне UI. */
    fun checkConnection(): ConnectionState {
        val (checked, attempt) = synchronized(lock) {
            if (state !is ConnectionState.Connected) return state
            client to generation
        }
        val alive = try {
            checked != null && checked.isAlive() && checked.shell(ADB_PROBE_COMMAND).let {
                it.exitCode == 0 && it.trimmedOutput == ADB_PROBE_TOKEN
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val lost = synchronized(lock) {
            // Запоздавшая проверка не должна отключить новое соединение или воскресить старое.
            if (generation != attempt || client !== checked || alive) false else {
                generation++
                client = null
                state = ConnectionState.Disconnected
                true
            }
        }
        if (lost) runCatching { checked?.close() }
        return state
    }

    /**
     * Подключается по введённому адресу.
     *
     * Если соединение с этим же адресом уже открыто и живо, переиспользует его:
     * второе подключение к тому же adbd конфликтует с первым — на десктопе это
     * уже обходили тем же способом.
     */
    fun connect(input: String): ConnectionState {
        val address = parseDeviceAddress(input)
        if (address == null) {
            state = ConnectionState.Failed(null, ConnectionError.INVALID_ADDRESS)
            return state
        }
        return connect(address)
    }

    fun connect(address: DeviceAddress): ConnectionState = connectTarget(address) { factory.connect(address) }

    fun connectUsb(address: UsbDeviceAddress): ConnectionState = connectTarget(address) { usbConnect(address) }

    private fun connectTarget(address: DeviceTarget, open: () -> AdbClient): ConnectionState {
        val (previous, attempt) = synchronized(lock) {
            val existing = client
            if (existing != null && state.targetOrNull() == address && existing.isAlive()) {
                state = ConnectionState.Connected(address)
                return state
            }
            client = null
            generation++
            state = ConnectionState.Connecting(address)
            existing to generation
        }
        previous?.close()
        return try {
            val opened = open()
            val accepted = synchronized(lock) {
                if (generation != attempt) false else {
                    client = opened
                    state = ConnectionState.Connected(address)
                    true
                }
            }
            // Кабель/Activity могли исчезнуть, пока шли AUTH и проверка связи.
            if (!accepted) opened.close()
            state
        } catch (e: AdbConnectionException) {
            val diagnosticId = runCatching { onFailure(address, e) }.getOrNull()
            synchronized(lock) {
                if (generation == attempt) state = ConnectionState.Failed(address, e.reason, diagnosticId)
            }
            state
        }
    }

    /**
     * Спаривает устройство и сразу подключается по адресу подключения.
     *
     * Порты спаривания и подключения на устройстве разные — это главный
     * источник путаницы, поэтому они передаются раздельно и никогда не
     * выводятся один из другого.
     */
    suspend fun pairAndConnect(
        pairingInput: String,
        pairingCode: String,
        connectInput: String,
    ): ConnectionState {
        val pairingAddress = parseDeviceAddress(pairingInput)
        val connectAddress = parseDeviceAddress(connectInput)
        if (pairingAddress == null || connectAddress == null) {
            state = ConnectionState.Failed(null, ConnectionError.INVALID_ADDRESS)
            return state
        }
        if (!isValidPairingCode(pairingCode)) {
            state = ConnectionState.Failed(pairingAddress, ConnectionError.PAIRING_REJECTED)
            return state
        }

        return try {
            factory.pair(pairingAddress, pairingCode.trim())
            connect(connectAddress)
        } catch (e: AdbConnectionException) {
            val diagnosticId = runCatching { onFailure(pairingAddress, e) }.getOrNull()
            state = ConnectionState.Failed(pairingAddress, e.reason, diagnosticId)
            state
        }
    }

    /**
     * Подключение для режима телевизора: попытка достучаться до собственного
     * adbd по loopback.
     *
     * Это гипотеза, а не гарантия: не всякая прошивка принимает adb-соединение
     * с самой себя. Поэтому неудача здесь — обычный [ConnectionState.Failed], с
     * которым интерфейс предлагает ввести адрес вручную, а не аварийная
     * ситуация.
     */
    fun connectLoopback(): ConnectionState = connect(LOOPBACK_ADDRESS)

    /** Адрес, который стоит предложить пользователю при старте. */
    fun suggestedAddress(mode: DeviceMode, lastUsed: String?): DeviceAddress? = when {
        mode == DeviceMode.TELEVISION -> LOOPBACK_ADDRESS
        else -> lastUsed?.let { parseDeviceAddress(it) }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            generation++
            val previous = client
            client = null
            state = ConnectionState.Disconnected
            previous
        }
        previous?.close()
    }
}

/** Адрес из состояния, если он там есть. */
fun ConnectionState.targetOrNull(): DeviceTarget? = when (this) {
    is ConnectionState.Connected -> address
    is ConnectionState.Connecting -> address
    is ConnectionState.Checking -> address
    is ConnectionState.Failed -> address
    ConnectionState.Disconnected -> null
}

fun ConnectionState.addressOrNull(): DeviceAddress? = targetOrNull() as? DeviceAddress
