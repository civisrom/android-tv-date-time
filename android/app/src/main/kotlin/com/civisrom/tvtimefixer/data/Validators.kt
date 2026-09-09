package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.adb.DeviceTarget

/** Порт adbd для «отладки по сети». Беспроводная отладка использует случайный. */
const val DEFAULT_ADB_PORT = 5555

/**
 * Проверяет адрес NTP-сервера: либо IPv4, либо доменное имя.
 *
 * Каждая метка домена проверяется отдельно; адрес отклоняется до сетевого
 * запроса или записи настройки на устройство.
 */
// На Android \d включает Unicode-цифры, которые не являются цифрами IPv4/ADB.
private val IPV4 = Regex("""^([0-9]{1,3}\.){3}[0-9]{1,3}$""")
private val DOMAIN = Regex("""^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$""")

fun isValidIpv4(value: String): Boolean {
    if (!IPV4.matches(value)) return false
    return value.split('.').all { octet ->
        val number = octet.toIntOrNull() ?: return false
        number in 0..255
    }
}

fun isValidNtpServer(server: String): Boolean {
    val value = server.trim()
    if (value.isEmpty() || value.length > 253) return false
    return isValidIpv4(value) || DOMAIN.matches(value)
}

/** Разобранный адрес устройства. */
data class DeviceAddress(val host: String, val port: Int) : DeviceTarget {
    override fun toString(): String = "$host:$port"
}

/**
 * Разбирает «ip» или «ip:port».
 *
 * Возвращает null, если адрес некорректен, — в том числе при порте вне
 * диапазона. Десктопная версия здесь намеренно не подставляет порт по
 * умолчанию молча, чтобы опечатка не превращалась в обращение не туда.
 */
fun parseDeviceAddress(input: String): DeviceAddress? {
    val value = input.trim()
    if (value.isEmpty()) return null

    val separator = value.lastIndexOf(':')
    if (separator < 0) {
        return if (isValidIpv4(value)) DeviceAddress(value, DEFAULT_ADB_PORT) else null
    }

    val host = value.substring(0, separator)
    val portText = value.substring(separator + 1)
    if (portText.length !in 1..5 || portText.any { it !in '0'..'9' }) return null
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return if (isValidIpv4(host)) DeviceAddress(host, port) else null
}

/** Код спаривания Android 11+ — ровно шесть цифр. */
fun isValidPairingCode(code: String): Boolean =
    Regex("""^[0-9]{6}$""").matches(code.trim())
