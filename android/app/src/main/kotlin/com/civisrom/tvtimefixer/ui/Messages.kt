package com.civisrom.tvtimefixer.ui

import androidx.annotation.StringRes
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.data.NtpProbeFailure
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import com.civisrom.tvtimefixer.device.TimeZoneFailure

/**
 * Сопоставление доменных результатов пользовательским строкам.
 *
 * Вынесено отдельно от интерфейса, чтобы проверяться на JVM: забытая ветка
 * when здесь означает, что человек увидит пустоту вместо объяснения, почему
 * подключение не удалось.
 */
@StringRes
fun ConnectionError.messageRes(): Int = when (this) {
    ConnectionError.INVALID_ADDRESS -> R.string.error_invalid_address
    ConnectionError.UNREACHABLE -> R.string.error_unreachable
    ConnectionError.PAIRING_REQUIRED -> R.string.error_pairing_required
    ConnectionError.NOT_AUTHORIZED -> R.string.error_not_authorized
    ConnectionError.PAIRING_REJECTED -> R.string.error_pairing_rejected
    ConnectionError.PAIRING_TIMEOUT -> R.string.error_pairing_timeout
    ConnectionError.PAIRING_FAILED -> R.string.error_pairing_failed
    ConnectionError.TLS_FAILED -> R.string.error_tls_failed
    ConnectionError.WIRELESS_UNSUPPORTED -> R.string.error_wireless_unsupported
    ConnectionError.USB_UNSUPPORTED -> R.string.error_usb_unsupported
    ConnectionError.USB_PERMISSION_DENIED -> R.string.error_usb_permission
    ConnectionError.USB_DISCONNECTED -> R.string.error_usb_disconnected
    ConnectionError.USB_BUSY -> R.string.error_usb_busy
    ConnectionError.USB_IO -> R.string.error_usb_io
    ConnectionError.UNKNOWN -> R.string.error_unknown
}

@StringRes
fun DiscoveredDevice.Kind.labelRes(): Int = when (this) {
    DiscoveredDevice.Kind.AWAITING_PAIRING -> R.string.discovery_kind_awaiting_pairing
    DiscoveredDevice.Kind.READY_TO_CONNECT -> R.string.discovery_kind_ready
    DiscoveredDevice.Kind.LEGACY -> R.string.discovery_kind_legacy
}

/** Строка результата смены сервера вместе с подстановками для неё. */
data class UiMessage(@StringRes val res: Int, val args: List<String> = emptyList())

@StringRes
fun TimeZoneFailure.messageRes(): Int = when (this) {
    TimeZoneFailure.INVALID_ZONE -> R.string.time_zone_invalid
    TimeZoneFailure.UNSUPPORTED -> R.string.time_zone_unsupported
    TimeZoneFailure.READ_STATE -> R.string.time_zone_read_failed
    TimeZoneFailure.AUTO_MODE -> R.string.time_zone_auto_failed
    TimeZoneFailure.WRITE -> R.string.time_zone_write_failed
}

@StringRes
fun NtpProbeResult.rejectionMessageRes(): Int = if (reachable) R.string.ntp_check_invalid_response else when (failure) {
    NtpProbeFailure.INVALID_ADDRESS -> R.string.ntp_invalid
    NtpProbeFailure.DNS -> R.string.ntp_check_dns
    NtpProbeFailure.TIMEOUT -> R.string.ntp_check_timeout
    NtpProbeFailure.INVALID_RESPONSE -> R.string.ntp_check_invalid_response
    NtpProbeFailure.NETWORK, null -> R.string.ntp_check_network
}

fun NtpUpdateResult.toUiMessage(): UiMessage = when (this) {
    is NtpUpdateResult.Applied -> if (server == "null") UiMessage(R.string.ntp_default_applied)
        else UiMessage(R.string.ntp_applied, listOf(server))
    NtpUpdateResult.InvalidServer -> UiMessage(R.string.ntp_invalid)
    is NtpUpdateResult.NotConfirmed -> UiMessage(
        R.string.ntp_not_confirmed,
        listOf(actual.ifEmpty { "—" }),
    )
    is NtpUpdateResult.Failed -> UiMessage(R.string.ntp_failed, listOf(message))
}

@StringRes
fun DeviceTimeStatus.messageRes(): Int = when (this) {
    DeviceTimeStatus.MATCH -> R.string.time_check_match
    DeviceTimeStatus.MISMATCH -> R.string.time_check_mismatch
    DeviceTimeStatus.UNCERTAIN -> R.string.time_check_uncertain
    DeviceTimeStatus.NO_SERVER -> R.string.time_check_no_server
    DeviceTimeStatus.SYSTEM_DEFAULT -> R.string.time_check_system_default
    DeviceTimeStatus.NTP_UNAVAILABLE -> R.string.time_check_ntp_unavailable
    DeviceTimeStatus.DEVICE_UNAVAILABLE -> R.string.time_check_unavailable
}
