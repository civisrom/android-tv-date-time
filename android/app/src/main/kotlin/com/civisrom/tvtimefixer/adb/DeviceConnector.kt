package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.DEFAULT_ADB_PORT
import com.civisrom.tvtimefixer.data.isValidPairingCode
import com.civisrom.tvtimefixer.data.parseDeviceAddress

/** Состояние подключения к устройству. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: DeviceAddress) : ConnectionState
    data class Connected(val address: DeviceAddress) : ConnectionState
    data class Failed(val address: DeviceAddress?, val reason: ConnectionError) : ConnectionState
}

/** Адрес самого устройства, на котором запущено приложение (режим телевизора). */
val LOOPBACK_ADDRESS = DeviceAddress("127.0.0.1", DEFAULT_ADB_PORT)

/**
 * Управляет подключением: разбирает адрес, открывает соединение, помнит его.
 *
 * Вся работа с сетью спрятана за [AdbClientFactory], поэтому эта логика
 * целиком проверяется на JVM — без телевизора и без эмулятора.
 */
class DeviceConnector(private val factory: AdbClientFactory) {

    var state: ConnectionState = ConnectionState.Disconnected
        private set

    private var client: AdbClient? = null

    /** Текущее соединение, если оно живое. */
    val activeClient: AdbClient?
        get() = client?.takeIf { it.isAlive() }

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

    fun connect(address: DeviceAddress): ConnectionState {
        val existing = client
        if (existing != null && state.addressOrNull() == address && existing.isAlive()) {
            state = ConnectionState.Connected(address)
            return state
        }

        disconnect()
        state = ConnectionState.Connecting(address)
        return try {
            client = factory.connect(address)
            state = ConnectionState.Connected(address)
            state
        } catch (e: AdbConnectionException) {
            client = null
            state = ConnectionState.Failed(address, e.reason)
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
            state = ConnectionState.Failed(pairingAddress, e.reason)
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
        client?.close()
        client = null
        state = ConnectionState.Disconnected
    }
}

/** Адрес из состояния, если он там есть. */
fun ConnectionState.addressOrNull(): DeviceAddress? = when (this) {
    is ConnectionState.Connected -> address
    is ConnectionState.Connecting -> address
    is ConnectionState.Failed -> address
    ConnectionState.Disconnected -> null
}
