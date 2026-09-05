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
import java.util.concurrent.RejectedExecutionException
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
    private val callbacks = mutableMapOf<String, Any>()
    private val services = mutableMapOf<String, Any>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, DiscoveredDevice.Kind>>()
    private var resolving = false
    private var running = false
    private var closed = false
    private var failures = 0
    private var generation = 0L

    private val flow = MutableStateFlow(
        DiscoveryState(available = true, searching = false, devices = emptyList()),
    )

    override val state: StateFlow<DiscoveryState> = flow

    override fun start() {
        val epoch = synchronized(lock) {
            if (closed || running) return
            running = true
            failures = 0
            ++generation
        }
        publish()
        SERVICE_TYPES.forEach { (type, kind) ->
            synchronized(lock) {
                if (!active(epoch)) return
                val listener = discoveryListener(kind, epoch)
                listeners += listener
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure { discoveryFailed(epoch) }
            }
        }
    }

    override fun stop() {
        val stopping: List<NsdManager.DiscoveryListener>
        val unregistering: List<Any>
        synchronized(lock) {
            if (!running) return
            running = false
            generation++
            stopping = listeners.toList()
            listeners.clear()
            devices.clear()
            pending.clear()
            resolving = false
            services.clear()
            unregistering = callbacks.values.toList()
            callbacks.clear()
        }
        stopping.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        unregistering.forEach(::unregisterCallback)
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

    private fun active(epoch: Long) = running && !closed && generation == epoch

    private fun discoveryListener(kind: DiscoveredDevice.Kind, epoch: Long) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                discoveryFailed(epoch)

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Тип берётся из самого сервиса, а не из того, что мы искали:
                // прошивки отдают его в разном виде, и только он разделяет
                // порт спаривания и порт подключения
                val found = serviceKindOf(info.serviceType) ?: kind
                enqueue(info, found, epoch)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val found = serviceKindOf(info.serviceType) ?: kind
                val callback = synchronized(lock) {
                    if (!active(epoch)) return
                    val key = keyOf(info.serviceName, found)
                    devices.remove(key)
                    services.remove(key)
                    pending.removeAll { keyOf(it.first.serviceName, it.second) == key }
                    callbacks.remove(key)
                }
                callback?.let(::unregisterCallback)
                publish()
            }
        }

    /** Все три типа могут не запуститься — тогда обнаружения нет вовсе. */
    private fun discoveryFailed(epoch: Long) {
        synchronized(lock) {
            if (!active(epoch)) return
            failures += 1
        }
        publish()
    }

    // ── Резолв: по одному за раз ─────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun enqueue(info: NsdServiceInfo, kind: DiscoveredDevice.Kind, epoch: Long) {
        synchronized(lock) {
            if (!active(epoch)) return
            val key = keyOf(info.serviceName, kind)
            if (services.containsKey(key)) return
            val token = Any()
            services[key] = token
            // API 34 subscriptions are continuous; an initial update is not guaranteed.
            // Never put them behind the pre-34 one-shot resolver queue.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerInfoCallback(info, kind, epoch, token)
                return
            }
            pending.addLast(info to kind)
            if (resolving) return
            resolving = true
        }
        resolveNext(epoch)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(epoch: Long) {
        synchronized(lock) {
            if (!active(epoch)) return
            val item = pending.removeFirstOrNull()
            if (item == null) {
                resolving = false
                return
            }
            val (info, kind) = item
            val key = keyOf(info.serviceName, kind)
            val token = services[key] ?: return resolveNext(epoch)
            runCatching { nsd.resolveService(info, resolveListener(kind, epoch, key, token)) }
                .onFailure {
                    services.remove(key)
                    resolveNext(epoch)
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveListener(kind: DiscoveredDevice.Kind, epoch: Long, key: String, token: Any) =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) {
                    if (!active(epoch)) return
                    if (services[key] === token) services.remove(key)
                }
                resolveNext(epoch)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                remember(info, kind, epoch, key, token)
                resolveNext(epoch)
            }
        }

    /**
     * Android 14 и новее. `resolveService` там объявлен устаревшим, а
     * `registerServiceInfoCallback` вдобавок присылает обновления адреса.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerInfoCallback(info: NsdServiceInfo, kind: DiscoveredDevice.Kind, epoch: Long, token: Any) {
        val key = keyOf(info.serviceName, kind)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                synchronized(lock) {
                    if (!active(epoch) || services[key] !== token) return
                    services.remove(key)
                    callbacks.remove(key)
                }
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                remember(info, kind, epoch, key, token)
            }

            override fun onServiceLost() {
                synchronized(lock) {
                    if (!active(epoch) || services[key] !== token) return
                    devices.remove(key)
                }
                publish()
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks[key] = callback
        runCatching {
            nsd.registerServiceInfoCallback(info, { task ->
                try {
                    resolver.execute(task)
                } catch (_: RejectedExecutionException) {
                    // Late framework delivery after close; the session is already invalid.
                }
            }, callback)
        }.onFailure {
            callbacks.remove(key)
            services.remove(key)
        }
    }

    @SuppressLint("NewApi")
    private fun unregisterCallback(callback: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching { nsd.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) }
    }

    // ── Состояние ────────────────────────────────────────────────────────

    private fun remember(info: NsdServiceInfo, kind: DiscoveredDevice.Kind, epoch: Long, key: String, token: Any) {
        val host = hostOf(info) ?: return
        val port = info.port
        if (port !in 1..65535) return
        val device = DiscoveredDevice(
            name = info.serviceName.orEmpty().ifBlank { host },
            address = DeviceAddress(host, port),
            kind = kind,
        )
        synchronized(lock) {
            if (!active(epoch) || services[key] !== token) return
            devices[key] = device
        }
        publish()
    }

    private fun publish() {
        synchronized(lock) {
            flow.value = DiscoveryState(
                available = failures < SERVICE_TYPES.size,
                searching = running,
                devices = devices.values.toList(),
            )
        }
    }

    private companion object {
        val SERVICE_TYPES = listOf(
            SERVICE_TLS_PAIRING to DiscoveredDevice.Kind.AWAITING_PAIRING,
            SERVICE_TLS_CONNECT to DiscoveredDevice.Kind.READY_TO_CONNECT,
            SERVICE_LEGACY to DiscoveredDevice.Kind.LEGACY,
        )

        fun keyOf(name: String?, kind: DiscoveredDevice.Kind) = kind.name + "|" + name.orEmpty()

        /** The address input/parser currently supports IPv4 only, unlike ADB itself. */
        @SuppressLint("NewApi")
        fun hostOf(info: NsdServiceInfo): String? {
            val addresses: List<InetAddress> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    info.hostAddresses
                } else {
                    @Suppress("DEPRECATION")
                    listOfNotNull(info.host)
                }
            return addresses.firstOrNull { it is Inet4Address }?.hostAddress
        }
    }
}
