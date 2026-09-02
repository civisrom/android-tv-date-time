package com.civisrom.tvtimefixer.device

/**
 * Сведения об устройстве. Поля намеренно строковые: это то, что показывается
 * пользователю, а не то, по чему принимаются решения.
 */
data class DeviceInfo(
    val model: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val apiLevel: String = "",
    val serial: String = "",
    val cpuAbi: String = "",
    val timezone: String = "",
    val locale: String = "",
    val currentNtpServer: String = "",
    val batteryLevel: String = "",
    val totalRam: String = "",
    val availableRam: String = "",
    val screenResolution: String = "",
    val screenDensity: String = "",
    val cpuCores: String = "",
    val kernelVersion: String = "",
    val uptime: String = "",
)

/**
 * Разбирает вывод `getprop`.
 *
 * Значения свойств сами могут содержать ']' (например ro.build.description),
 * поэтому закрывающая скобка ищется в конце строки, а не первой попавшейся —
 * ровно как в десктопной версии.
 */
fun parseGetProp(raw: String): Map<String, String> {
    val pattern = Regex("""^\[([^\]]+)]:\s*\[(.*)]$""")
    return raw.lineSequence()
        .mapNotNull { pattern.find(it.trim())?.destructured }
        .associate { (key, value) -> key to value }
}

/** Достаёт «level: 87» из вывода dumpsys battery. */
fun parseBatteryLevel(raw: String): String =
    raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("level:") }
        ?.substringAfter("level:")
        ?.trim()
        .orEmpty()

/** Достаёт значение строки /proc/meminfo, например MemTotal. */
fun parseMemInfo(raw: String, key: String): String =
    raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key:") }
        ?.substringAfter(':')
        ?.trim()
        .orEmpty()

/** Первое число из /proc/uptime — секунды работы устройства. */
fun parseUptimeSeconds(raw: String): Long? =
    raw.trim().substringBefore(' ').toDoubleOrNull()?.toLong()

/** Человекочитаемое время работы. */
fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}
