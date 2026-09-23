package com.civisrom.tvtimefixer.data

import com.civisrom.tvtimefixer.adb.DeviceTarget
import java.net.InetAddress

/** Порт adbd для «отладки по сети». Беспроводная отладка использует случайный. */
const val DEFAULT_ADB_PORT = 5555

/**
 * Проверяет адрес NTP-сервера: IPv4, IPv6 либо доменное имя.
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
        // Leading zeroes can be interpreted as octal by network address parsers.
        if (octet.length > 1 && octet.startsWith('0')) return false
        val number = octet.toIntOrNull() ?: return false
        number in 0..255
    }
}

fun isValidIpv6(value: String, allowScope: Boolean = false): Boolean {
    val parts = value.split('%')
    if (parts.size > 2 || (parts.size == 2 &&
            (!allowScope || !Regex("[A-Za-z0-9_.-]{1,32}").matches(parts[1])))) return false
    val address = parts[0]
    // Only a literal reaches InetAddress: never resolve user input during UI validation.
    if (address.count { it == ':' } < 2 || !Regex("[0-9A-Fa-f:.]+").matches(address)) return false
    if ('.' in address && !isValidIpv4(address.substringAfterLast(':'))) return false
    return runCatching { InetAddress.getByName(address); true }.getOrDefault(false)
}

fun isValidNtpServer(server: String): Boolean {
    val value = server.trim()
    if (value.isEmpty() || value.length > 253) return false
    return isValidIpv4(value) || isValidIpv6(value) || DOMAIN.matches(value)
}

/** Разобранный адрес устройства. */
data class DeviceAddress(val host: String, val port: Int) : DeviceTarget {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

fun hasExplicitDevicePort(input: String): Boolean = input.trim().let {
    if (it.startsWith('[')) "]:" in it else it.count { char -> char == ':' } == 1
}

/**
 * Разбирает IPv4[:port], IPv6 или [IPv6%интерфейс]:port.
 *
 * Возвращает null, если адрес некорректен, — в том числе при порте вне
 * диапазона. Десктопная версия здесь намеренно не подставляет порт по
 * умолчанию молча, чтобы опечатка не превращалась в обращение не туда.
 */
fun parseDeviceAddress(input: String): DeviceAddress? {
    val value = input.trim()
    if (value.isEmpty()) return null

    var host = value
    var portText: String? = null
    if (value.startsWith('[')) {
        val match = Regex("""\[([^\[\]]+)\](?::([^:]*))?""").matchEntire(value) ?: return null
        host = match.groupValues[1]
        portText = match.groups[2]?.value
        if (!isValidIpv6(host, allowScope = true)) return null
    } else if (value.count { it == ':' } == 1) {
        host = value.substringBefore(':')
        portText = value.substringAfter(':')
    }
    if (!isValidIpv4(host) && !isValidIpv6(host, allowScope = true)) return null
    val port = if (portText == null) DEFAULT_ADB_PORT else {
        if (portText.length !in 1..5 || portText.any { it !in '0'..'9' }) return null
        portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    }
    return DeviceAddress(host, port)
}

/** Код спаривания Android 11+ — ровно шесть цифр. */
fun isValidPairingCode(code: String): Boolean =
    Regex("""^[0-9]{6}$""").matches(code.trim())
