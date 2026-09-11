package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.data.ScanProgress
import com.civisrom.tvtimefixer.device.DeviceInfo
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.UsbSystemState

/**
 * Всё, что показывает экран.
 *
 * Отдельный тип, а не набор разрозненных mutableStateOf: так состояние можно
 * проверить целиком, не поднимая Compose.
 */
data class AppState(
    val localSetup: com.civisrom.tvtimefixer.LocalSetupState = com.civisrom.tvtimefixer.LocalSetupState(),
    val favorites: com.civisrom.tvtimefixer.data.Favorites = com.civisrom.tvtimefixer.data.Favorites(),
    val favoritesReady: Boolean = false,
    val favoritesBusy: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val busy: Boolean = false,
    val operation: Operation? = null,
    val diagnosticEventId: Long? = null,
    val ntpDiagnosticEventId: Long? = null,
    val timeDiagnosticEventId: Long? = null,
    val timeZoneDiagnosticEventId: Long? = null,
    val timeZoneResult: TimeZoneUpdateResult? = null,
    val discoveryAvailable: Boolean = true,
    val discoverySearching: Boolean = false,
    val discoveryPermissionNeeded: Boolean = false,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val usbSupported: Boolean = false,
    val usbDevices: List<UsbDeviceAddress> = emptyList(),
    val usbAttachedCount: Int = 0,
    val usbScanFailed: Boolean = false,
    val usbSystemState: UsbSystemState = UsbSystemState(),
    val deviceInfo: DeviceInfo? = null,
    val deviceName: String = "",
    val message: UiMessage? = null,
    /**
     * Итог смены сервера времени — отдельно от [message] намеренно.
     *
     * Показывается рядом с кнопкой «Применить», а не в общей карточке вверху
     * экрана: раздел сервера времени находится далеко внизу, и подтверждение
     * там человек просто не видит. Ровно так и вышло на живом устройстве —
     * сервер менялся, а понять это было нельзя.
     */
    val ntpMessage: UiMessage? = null,
    /** Итог проверки одного адреса кнопкой «Проверить». */
    val ntpCheck: NtpProbeResult? = null,
    val ntpChange: NtpUpdateResult.Applied? = null,
    val ntpBeforeTime: DeviceTimeCheck? = null,
    /** Последний замер часов подключённого устройства; сбрасывается при новой настройке/связи. */
    val timeCheck: DeviceTimeCheck? = null,
    /** Идущий или законченный подбор лучшего сервера. */
    val ntpScan: ScanProgress? = null,
) {
    val currentNtpServer: String get() = deviceInfo?.currentNtpServer.orEmpty()

    /** Результат ADB меняет свои поля; фоновые службы продолжают владеть своими. */
    fun withLatestBackground(latest: AppState): AppState = copy(
        localSetup = latest.localSetup, favorites = latest.favorites,
        favoritesReady = latest.favoritesReady, favoritesBusy = latest.favoritesBusy,
        usbSupported = latest.usbSupported, usbDevices = latest.usbDevices,
        usbAttachedCount = latest.usbAttachedCount, usbScanFailed = latest.usbScanFailed,
        usbSystemState = latest.usbSystemState,
        discovered = latest.discovered, discoveryAvailable = latest.discoveryAvailable,
        discoverySearching = latest.discoverySearching, discoveryPermissionNeeded = latest.discoveryPermissionNeeded,
        ntpScan = latest.ntpScan,
    )

    /** После потери связи сведения и подтверждения от прежнего устройства больше не актуальны. */
    fun connectionLost(): AppState = copy(
        connection = ConnectionState.Disconnected,
        deviceName = "",
        deviceInfo = null, ntpMessage = null, ntpCheck = null, ntpDiagnosticEventId = null,
        ntpChange = null, ntpBeforeTime = null,
        timeCheck = null, timeDiagnosticEventId = null,
        timeZoneResult = null, timeZoneDiagnosticEventId = null,
    )

    val connected: Boolean get() = connection is ConnectionState.Connected

    /**
     * Адрес, с которым связь **действительно установлена**, иначе null.
     *
     * Отдельно от `addressOrNull()`: та отвечает на другой вопрос — «какой
     * адрес фигурирует в состоянии» — и возвращает его в том числе при отказе.
     * Спутать эти два вопроса уже стоило дорого: после отмены запроса на
     * экране телевизора найденное устройство подписывалось «Подключено» и
     * теряло кнопку, то есть повторить попытку было нечем.
     */
    val connectedAddress: DeviceAddress?
        get() = (connection as? ConnectionState.Connected)?.address as? DeviceAddress

    val connectedUsb: UsbDeviceAddress?
        get() = (connection as? ConnectionState.Connected)?.address as? UsbDeviceAddress
}
