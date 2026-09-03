package com.civisrom.tvtimefixer.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.civisrom.tvtimefixer.data.DeviceAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Обнаружение устройств поверх системного [NsdManager].
 *
 * Написано вместо `kadb-mdns-android` 2.1.3: там `AndroidServiceInfoCallback`
 * и `AndroidResolveListener` вызывают сами себя вместо переданной лямбды —
 * свойство названо так же, как переопределяемый метод интерфейса, и вызов
 * разрешается в метод. Любое найденное устройство роняло процесс с
 * `StackOverflowError`, на всех версиях Android: на 14+ через
 * `onServiceUpdated`, ниже — через `onServiceResolved`.
 *
 * Обнаружение остаётся необязательной помощью: всё, что здесь не получилось,
 * приводит к состоянию «недоступно», а не к исключению наружу. Адрес
 * устройства всегда можно ввести руками.
 */
class NsdDeviceDiscovery(context: Context) : DeviceDiscovery {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Резолв просится в один поток намеренно: до Android 14 `resolveService`
     * отвечает `IllegalArgumentException: listener already in use`, если
     * предыдущий запрос ещё не завершился, а найтись три сервиса могут разом.
     */
    private val resolver = Executors.newSingleThreadExecutor()

    private val lock = Any()
    private val devices = LinkedHashMap<String, DiscoveredDevice>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val callbacks = mutableListOf<Pair<String, Any>>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, DiscoveredDevice.Kind>>()
    private var resolving = false
    private var running = false
    private var closed = false
    private var failures = 0

    private val flow = MutableStateFlow(
        DiscoveryState(available = true, searching = false, devices = emptyList()),
    )

    override val state: StateFlow<DiscoveryState> = flow

    override fun start() {
        synchronized(lock) {
            if (closed || running) return
            running = true
            failures = 0
        }
        publish()
        SERVICE_TYPES.forEach { (type, kind) ->
            val listener = discoveryListener(kind)
            synchronized(lock) { listeners += listener }
            // Отказ одного типа не должен мешать остальным двум
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { discoveryFailed() }
        }
    }

    override fun stop() {
        val stopping: List<NsdManager.DiscoveryListener>
        synchronized(lock) {
            if (!running) return
            running = false
            stopping = listeners.toList()
            listeners.clear()
            devices.clear()
            pending.clear()
        }
        stopping.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        unregisterCallbacks()
        publish()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        resolver.shutdown()
    }

    // ── Обнаружение ──────────────────────────────────────────────────────

    private fun discoveryListener(kind: DiscoveredDevice.Kind) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                discoveryFailed()

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Тип берётся из самого сервиса, а не из того, что мы искали:
                // прошивки отдают его в разном виде, и только он разделяет
                // порт спаривания и порт подключения
                val found = serviceKindOf(info.serviceType) ?: kind
                enqueue(info, found)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { devices.remove(keyOf(info.serviceName, kind)) }
                publish()
            }
        }

    /** Все три типа могут не запуститься — тогда обнаружения нет вовсе. */
    private fun discoveryFailed() {
        synchronized(lock) { failures += 1 }
        publish()
    }

    // ── Резолв: по одному за раз ─────────────────────────────────────────

    private fun enqueue(info: NsdServiceInfo, kind: DiscoveredDevice.Kind) {
        synchronized(lock) {
            if (!running) return
            pending.addLast(info to kind)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            val item = pending.removeFirstOrNull()
            if (item == null) resolving = false
            item
        } ?: return
        val (info, kind) = next
        runCatching { resolve(info, kind) }.onFailure { resolveNext() }
    }

    @SuppressLint("NewApi")
    private fun resolve(info: NsdServiceInfo, kind: DiscoveredDevice.Kind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerInfoCallback(info, kind)
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, resolveListener(kind))
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveListener(kind: DiscoveredDevice.Kind) =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()

            override fun onServiceResolved(info: NsdServiceInfo) {
                remember(info, kind)
                resolveNext()
            }
        }

    /**
     * Android 14 и новее. `resolveService` там объявлен устаревшим, а
     * `registerServiceInfoCallback` вдобавок присылает обновления адреса.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerInfoCallback(info: NsdServiceInfo, kind: DiscoveredDevice.Kind) {
        val name = info.serviceName
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = resolveNext()

            override fun onServiceUpdated(info: NsdServiceInfo) {
                remember(info, kind)
                resolveNext()
            }

            override fun onServiceLost() {
                synchronized(lock) { devices.remove(keyOf(name, kind)) }
                publish()
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        synchronized(lock) { callbacks += name to callback }
        nsd.registerServiceInfoCallback(info, resolver, callback)
    }

    @SuppressLint("NewApi")
    private fun unregisterCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val current = synchronized(lock) { callbacks.toList().also { callbacks.clear() } }
        current.forEach { (_, callback) ->
            runCatching { nsd.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) }
        }
    }

    // ── Состояние ────────────────────────────────────────────────────────

    private fun remember(info: NsdServiceInfo, kind: DiscoveredDevice.Kind) {
        val host = hostOf(info) ?: return
        val port = info.port
        if (port !in 1..65535) return
        val device = DiscoveredDevice(
            name = info.serviceName.orEmpty().ifBlank { host },
            address = DeviceAddress(host, port),
            kind = kind,
        )
        synchronized(lock) { devices[keyOf(info.serviceName, kind)] = device }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            DiscoveryState(
                available = failures < SERVICE_TYPES.size,
                searching = running,
                devices = devices.values.toList(),
            )
        }
        flow.value = snapshot
    }

    private companion object {
        val SERVICE_TYPES = listOf(
            SERVICE_TLS_PAIRING to DiscoveredDevice.Kind.AWAITING_PAIRING,
            SERVICE_TLS_CONNECT to DiscoveredDevice.Kind.READY_TO_CONNECT,
            SERVICE_LEGACY to DiscoveredDevice.Kind.LEGACY,
        )

        fun keyOf(name: String?, kind: DiscoveredDevice.Kind) = kind.name + "|" + name.orEmpty()

        /** IPv4 предпочтительнее: adb по сети живёт именно там. */
        @SuppressLint("NewApi")
        fun hostOf(info: NsdServiceInfo): String? {
            val addresses: List<InetAddress> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    info.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(info.host)
                }
            val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
            return chosen?.hostAddress?.substringBefore('%')
        }
    }
}
