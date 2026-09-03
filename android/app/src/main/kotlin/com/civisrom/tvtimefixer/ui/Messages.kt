package com.civisrom.tvtimefixer.ui

import androidx.annotation.StringRes
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.device.NtpUpdateResult

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

fun NtpUpdateResult.toUiMessage(): UiMessage = when (this) {
    is NtpUpdateResult.Applied -> UiMessage(R.string.ntp_applied, listOf(server))
    NtpUpdateResult.InvalidServer -> UiMessage(R.string.ntp_invalid)
    is NtpUpdateResult.NotConfirmed -> UiMessage(
        R.string.ntp_not_confirmed,
        listOf(actual.ifEmpty { "—" }),
    )
    is NtpUpdateResult.Failed -> UiMessage(R.string.ntp_failed, listOf(message))
}
