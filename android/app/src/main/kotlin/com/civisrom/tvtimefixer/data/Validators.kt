package com.civisrom.tvtimefixer.data

/** Порт adbd для «отладки по сети». Беспроводная отладка использует случайный. */
const val DEFAULT_ADB_PORT = 5555

/**
 * Проверяет адрес NTP-сервера: либо IPv4, либо доменное имя.
 *
 * Перенесено с той же семантикой, что у validate_ntp_server в десктопной
 * версии, включая её особенность: запрет дефиса по краям действует только для
 * первой метки домена. Расходиться в правилах проверки нельзя — иначе адрес,
 * принятый на телефоне, будет отвергнут на компьютере.
 */
private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
private val DOMAIN = Regex("""^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\.[A-Za-z0-9-]{1,63})*\.[A-Za-z]{2,}$""")

fun isValidIpv4(value: String): Boolean {
    if (!IPV4.matches(value)) return false
    return value.split('.').all { octet ->
        val number = octet.toIntOrNull() ?: return false
        number in 0..255
    }
}

fun isValidNtpServer(server: String): Boolean {
    val value = server.trim()
    if (value.isEmpty()) return false
    return isValidIpv4(value) || DOMAIN.matches(value)
}

/** Разобранный адрес устройства. */
data class DeviceAddress(val host: String, val port: Int) {
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
    val port = value.substring(separator + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return if (isValidIpv4(host)) DeviceAddress(host, port) else null
}

/** Код спаривания Android 11+ — ровно шесть цифр. */
fun isValidPairingCode(code: String): Boolean =
    Regex("""^\d{6}$""").matches(code.trim())
