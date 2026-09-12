package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.DEFAULT_ADB_PORT
import com.civisrom.tvtimefixer.data.isValidPairingCode
import com.civisrom.tvtimefixer.data.parseDeviceAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

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
            return failInput(null, ConnectionError.INVALID_ADDRESS)
        }
        return connect(address)
    }

    fun connect(address: DeviceAddress): ConnectionState = connectTarget(address) { factory.connect(address) }

    fun connectUsb(address: UsbDeviceAddress): ConnectionState = connectTarget(address) { usbConnect(address) }

    private fun failInput(address: DeviceTarget?, reason: ConnectionError): ConnectionState {
        val previous = synchronized(lock) {
            val previous = client
            client = null
            generation++
            state = ConnectionState.Failed(address, reason)
            previous
        }
        previous?.close()
        return state
    }

    private fun connectTarget(address: DeviceTarget, expectedGeneration: Int? = null, open: () -> AdbClient): ConnectionState {
        val (previous, attempt) = synchronized(lock) {
            if (expectedGeneration != null && generation != expectedGeneration) return state
            val existing = client
            if (existing != null && state.targetOrNull() == address && existing.isAlive()) {
                state = ConnectionState.Connected(address)
                return state
            }
            client = null
            // The post-pair connection belongs to the same cancellable attempt.
            if (expectedGeneration == null) generation++
            state = ConnectionState.Connecting(address)
            existing to generation
        }
        previous?.close()
        return try {
            var opened: AdbClient
            var retries = 0
            val retryStarted = System.nanoTime()
            while (true) {
                try {
                    opened = open()
                    break
                } catch (e: AdbConnectionException) {
                    if (synchronized(lock) { generation != attempt }) return state
                    // Android persists a paired key asynchronously after the exchange.
                    // Only this initial connection may briefly retry TLS rejection;
                    // a wrong code and ordinary connections never enter this path.
                    val delayMs = 250L shl minOf(retries, 2)
                    if (expectedGeneration == null || e.reason != ConnectionError.TLS_FAILED || retries == 4 ||
                        System.nanoTime() - retryStarted + delayMs * 1_000_000 > 5_000_000_000L) throw e
                    retries++
                    Thread.sleep(delayMs)
                    if (synchronized(lock) { generation != attempt }) return state
                }
            }
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
        // Wireless debugging advertises both ports; the legacy default 5555 is not a substitute.
        val pairingAddress = parseDeviceAddress(pairingInput).takeIf { ':' in pairingInput }
        val connectAddress = parseDeviceAddress(connectInput).takeIf { ':' in connectInput }
        if (pairingAddress == null || connectAddress == null) {
            return failInput(null, ConnectionError.INVALID_ADDRESS)
        }
        if (!isValidPairingCode(pairingCode)) {
            return failInput(pairingAddress, ConnectionError.PAIRING_REJECTED)
        }

        val (previous, attempt) = synchronized(lock) {
            val previous = client
            client = null
            generation++
            state = ConnectionState.Connecting(connectAddress)
            previous to generation
        }
        previous?.close()
        return try {
            factory.pair(pairingAddress, pairingCode.trim())
            currentCoroutineContext().ensureActive()
            runInterruptible {
                connectTarget(connectAddress, attempt) { factory.connect(connectAddress) }
            }
        } catch (e: CancellationException) {
            val abandoned = synchronized(lock) {
                if (generation == attempt) {
                    generation++
                    val abandoned = client
                    client = null
                    state = ConnectionState.Disconnected
                    abandoned
                } else null
            }
            runCatching { abandoned?.close() }
            throw e
        } catch (e: AdbConnectionException) {
            val diagnosticId = runCatching { onFailure(pairingAddress, e) }.getOrNull()
            synchronized(lock) {
                if (generation == attempt) state = ConnectionState.Failed(pairingAddress, e.reason, diagnosticId)
            }
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
