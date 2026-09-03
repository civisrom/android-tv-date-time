package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import kotlinx.coroutines.flow.Flow

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

/**
 * Имена mDNS-сервисов, которые объявляет adbd.
 *
 * Порт спаривания и порт подключения — разные сервисы, и это единственный
 * способ отличить устройство, ждущее кода, от готового к подключению.
 */
const val SERVICE_LEGACY = "_adb._tcp"
const val SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp"
const val SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp"

/**
 * Вид устройства по типу mDNS-сервиса, либо null для чужого сервиса.
 *
 * Сравнивать строку напрямую нельзя: NsdManager отдаёт тип по-разному —
 * с завершающей точкой, в другом регистре, иногда вместе с доменом
 * (`_adb._tcp.local.`). Разные прошивки расходятся здесь между собой, а цена
 * промаха — пустой список устройств без единой жалобы.
 */
fun serviceKindOf(serviceType: String): DiscoveredDevice.Kind? {
    var value = serviceType.trim().lowercase().trimEnd('.')
    if (value.endsWith(".local")) value = value.removeSuffix(".local").trimEnd('.')
    return when (value) {
        SERVICE_TLS_PAIRING -> DiscoveredDevice.Kind.AWAITING_PAIRING
        SERVICE_TLS_CONNECT -> DiscoveredDevice.Kind.READY_TO_CONNECT
        SERVICE_LEGACY -> DiscoveredDevice.Kind.LEGACY
        else -> null
    }
}
