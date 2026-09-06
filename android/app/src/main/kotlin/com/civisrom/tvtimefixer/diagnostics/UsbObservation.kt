package com.civisrom.tvtimefixer.diagnostics

/** Неизвестное значение не подменяется false: прошивки могут не отдавать USB_STATE. */
data class UsbSystemState(
    val hostConnected: Boolean? = null,
    val deviceConnected: Boolean? = null,
    val configured: Boolean? = null,
)

/** Только счётчики и флаги; идентификаторы устройств и произвольные extras не принимаются. */
data class UsbObservation(
    val serviceAvailable: Boolean,
    val hostFeature: Boolean,
    val attachedCount: Int?,
    val adbCount: Int?,
    val system: UsbSystemState,
) {
    internal fun details(): String = listOf(
        "usb.service=$serviceAvailable",
        "usb.host_feature=$hostFeature",
        "usb.devices=${attachedCount?.coerceAtLeast(0) ?: "unknown"}",
        "usb.adb_devices=${adbCount?.coerceAtLeast(0) ?: "unknown"}",
        "usb.host_connected=${system.hostConnected ?: "unknown"}",
        "usb.device_connected=${system.deviceConnected ?: "unknown"}",
        "usb.configured=${system.configured ?: "unknown"}",
    ).joinToString("\n")
}
