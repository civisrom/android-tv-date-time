package com.civisrom.tvtimefixer.adb

import android.content.Context
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsConfig
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.mdns.MdnsStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Найденное в сети устройство. */
data class DiscoveredDevice(
    val name: String,
    val address: DeviceAddress,
    val kind: Kind,
) {
    enum class Kind {
        /** На экране устройства открыт диалог спаривания — нужен код. */
        AWAITING_PAIRING,

        /** Устройство уже спарено и готово к подключению. */
        READY_TO_CONNECT,

        /** Классическая «отладка по сети» на открытом порту. */
        LEGACY,
    }
}

/** Что сейчас с обнаружением. */
data class DiscoveryState(
    val available: Boolean,
    val searching: Boolean,
    val devices: List<DiscoveredDevice>,
)

/**
 * Обнаружение устройств по mDNS.
 *
 * Ради него всё и затевалось: беспроводная отладка выдаёт случайные порты,
 * причём для спаривания и подключения разные, и меняет их при каждом
 * включении. Без mDNS пользователю пришлось бы переписывать их с экрана.
 */
interface DeviceDiscovery : AutoCloseable {
    val state: Flow<DiscoveryState>
    fun start()
    fun stop()
}

/** Реализация поверх KadbMdns, которая внутри использует системный NsdManager. */
class KadbDeviceDiscovery(context: Context) : DeviceDiscovery {

    private val mdns = KadbMdnsAndroid(
        context.applicationContext,
        MdnsConfig(
            serviceTypes = setOf(
                MdnsServiceType.TLS_CONNECT,
                MdnsServiceType.TLS_PAIRING,
                MdnsServiceType.ADB,
            ),
            preferIpv4 = true,
        ),
    )

    override val state: Flow<DiscoveryState> = mdns.state.map { discovery ->
        DiscoveryState(
            // FAILED означает, что системный сервис обнаружения недоступен;
            // это не ошибка приложения, а повод предложить ввод адреса вручную
            available = discovery.status != MdnsStatus.FAILED,
            searching = discovery.loading,
            devices = discovery.allDevices.map { endpoint ->
                DiscoveredDevice(
                    name = endpoint.name,
                    address = DeviceAddress(endpoint.host, endpoint.port),
                    kind = when (endpoint.serviceType) {
                        MdnsServiceType.TLS_PAIRING -> DiscoveredDevice.Kind.AWAITING_PAIRING
                        MdnsServiceType.TLS_CONNECT -> DiscoveredDevice.Kind.READY_TO_CONNECT
                        MdnsServiceType.ADB -> DiscoveredDevice.Kind.LEGACY
                    },
                )
            },
        )
    }

    override fun start() = mdns.start()

    override fun stop() = mdns.stop()

    override fun close() = mdns.close()
}
