package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.device.DeviceInfo

/**
 * Всё, что показывает экран.
 *
 * Отдельный тип, а не набор разрозненных mutableStateOf: так состояние можно
 * проверить целиком, не поднимая Compose.
 */
data class AppState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val busy: Boolean = false,
    val discoveryAvailable: Boolean = true,
    val discoverySearching: Boolean = false,
    val discoveryPermissionNeeded: Boolean = false,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val deviceInfo: DeviceInfo? = null,
    val currentNtpServer: String = "",
    val message: UiMessage? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected

    /** Устройства, к которым можно подключаться прямо сейчас. */
    val connectable: List<DiscoveredDevice>
        get() = discovered.filter { it.kind != DiscoveredDevice.Kind.AWAITING_PAIRING }

    /** Устройства, ждущие кода спаривания. */
    val pairable: List<DiscoveredDevice>
        get() = discovered.filter { it.kind == DiscoveredDevice.Kind.AWAITING_PAIRING }
}
