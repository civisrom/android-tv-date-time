package com.civisrom.tvtimefixer.ui

import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.data.ScanProgress
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
    /** Идущий или законченный подбор лучшего сервера. */
    val ntpScan: ScanProgress? = null,
    /**
     * Адрес, который не прошёл проверку и ждёт решения человека.
     *
     * Проверка идёт из сети телефона, а UDP-порт 123 закрывают и мобильные
     * операторы, и часть домашних роутеров. Запрещать в такой обстановке
     * наглухо — значит не дать задать вообще ничего, поэтому отказ
     * сопровождается кнопкой «Применить всё-таки».
     */
    val ntpRejected: String? = null,
) {
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
        get() = (connection as? ConnectionState.Connected)?.address
}
