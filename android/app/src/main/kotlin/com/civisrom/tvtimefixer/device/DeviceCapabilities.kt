package com.civisrom.tvtimefixer.device

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

data class DisplayDetails(
    val activeMode: String = "",
    val supportedModes: String = "",
    /** null: не получены; пустой список: устройство сообщило отсутствие HDR. */
    val hdrTypes: List<Int>? = null,
    val allm: Boolean? = null,
)

/** AOSP DisplayDeviceInfo; не смешиваем основной экран с виртуальными дисплеями. */
internal fun parseDisplayDetails(raw: String): DisplayDetails {
    val displays = raw.lineSequence().filter { "DisplayDeviceInfo{" in it }
        .filterNot { "type VIRTUAL" in it }.map(String::trim).distinct().toList()
    val primary = displays.filter { "FLAG_DEFAULT_DISPLAY" in it }
    val display = primary.singleOrNull() ?: displays.singleOrNull() ?: return DisplayDetails()
    val modeId = Regex(""", modeId (\d+)""").find(display)?.groupValues?.get(1)
    // Stop at the next field, not at a mode's nested HDR / refresh-rate array.
    val modesText = display.substringAfter("supportedModes [", "").substringBefore(", colorMode")
    val modes = Regex("""\{id=(\d+), width=(\d+), height=(\d+), fps=([0-9.]+)""")
        .findAll(modesText).mapNotNull { match ->
            val (id, width, height, fps) = match.destructured
            val rate = fps.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            if (width.toIntOrNull()?.let { it > 0 } != true || height.toIntOrNull()?.let { it > 0 } != true) return@mapNotNull null
            id to "$width × $height @ ${String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.')} Hz"
        }.toList()
    val hdr = Regex("""hdrCapabilities HdrCapabilities\{mSupportedHdrTypes=\[([0-9, ]*)]""")
        .find(display)?.groupValues?.get(1)?.let { ids ->
            ids.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
        }
    return DisplayDetails(
        activeMode = modes.firstOrNull { it.first == modeId }?.second.orEmpty(),
        supportedModes = modes.map { it.second }.distinct().joinToString("\n"),
        hdrTypes = hdr,
        allm = Regex(""", allmSupported (true|false)\b""").find(display)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
    )
}

/**
 * Ответ на `df -k /data`, либо старый `df /data` (toolbox Android 6).
 * Toybox может показать другую точку монтирования
 * той же файловой системы (например /data/user/0), поэтому не сравниваем её с /data.
 */
internal fun parseDataStorage(raw: String): Pair<String, String> {
    val rows = raw.lineSequence().map { it.trim().split(Regex("\\s+")) }.toList()
    fun format(kib: Double) = String.format(Locale.ROOT, "%.2f GiB", kib / 1048576.0)
    if (rows.any { it == listOf("Filesystem", "Size", "Used", "Free", "Blksize") }) {
        val row = rows.filter { it.size == 5 && it[0].startsWith('/') }.singleOrNull() ?: return "" to ""
        fun kib(value: String): Double? {
            val match = Regex("""([0-9]+(?:\.[0-9]+)?)([KMG])""").matchEntire(value) ?: return null
            val amount = match.groupValues[1].toDoubleOrNull() ?: return null
            return amount * when (match.groupValues[2]) { "M" -> 1024.0; "G" -> 1048576.0; else -> 1.0 }
        }
        val total = kib(row[1]) ?: return "" to ""
        val free = kib(row[3]) ?: return "" to ""
        if (!total.isFinite() || total <= 0 || !free.isFinite() || free !in 0.0..total) return "" to ""
        return format(total) to format(free)
    }
    val row = rows.asSequence()
        .filter { it.size >= 5 && it.last().startsWith('/') && it[it.size - 2].matches(Regex("\\d+%")) }
        .singleOrNull() ?: return "" to ""
    val total = row.getOrNull(row.size - 5)?.toLongOrNull() ?: return "" to ""
    val free = row.getOrNull(row.size - 3)?.toLongOrNull() ?: return "" to ""
    if (total <= 0 || free !in 0..total) return "" to ""
    return format(total.toDouble()) to format(free.toDouble())
}

internal fun parseAutomaticSetting(raw: String): Boolean? = when (raw.trim()) {
    "1" -> true
    "0" -> false
    else -> null
}

/** Только текущие доступные выходы; не профили отключённых устройств из HW Modules. */
internal fun parseAudioOutputs(raw: String): Pair<String, String> {
    val lines = raw.lines()
    val start = lines.indexOfFirst { it.trim().removePrefix("- ").startsWith("Available output devices") }
    if (start < 0) return "" to ""
    val indent = lines[start].indexOfFirst { !it.isWhitespace() }
    val section = lines.drop(start + 1).takeWhile {
        it.isBlank() || it.indexOfFirst { char -> !char.isWhitespace() } > indent
    }.joinToString("\n")
    fun tokens(pattern: String, prefix: String) = Regex(pattern).findAll(section)
        .map { it.value.removePrefix(prefix) }.distinct().sorted().joinToString(", ")
    return tokens("\\bAUDIO_DEVICE_OUT_[A-Z0-9_]+\\b", "AUDIO_DEVICE_OUT_") to
        tokens("\\bAUDIO_FORMAT_[A-Z0-9_]+\\b", "AUDIO_FORMAT_")
}

/** Только адреса интерфейсов; имена Wi-Fi, MAC-адреса и история соединений не читаются. */
internal fun parseNetworkAddresses(raw: String): String = raw.lineSequence().mapNotNull { line ->
    val match = Regex("""^\d+:\s+(\S+)\s+inet6?\s+([0-9a-fA-F.:]+/\d+)\s+.*\bscope global\b""")
        .find(line.trim()) ?: return@mapNotNull null
    "${match.groupValues[1]}: ${match.groupValues[2]}"
}.distinct().sorted().joinToString("\n")

internal const val CODEC_XML_MARKER = "__TVTF_CODEC_XML__"

// Фиксированные системные пути, ограничение числа и размера файлов. Ничего на ТВ не пишем.
// Это объявления прошивки, а не результат MediaCodecList или тест воспроизведения.
internal val READ_CODEC_XML_COMMAND = """
    tvtf_count=0; for tvtf_file in /odm/etc/media_codecs*.xml /vendor/etc/media_codecs*.xml /system/etc/media_codecs*.xml /system_ext/etc/media_codecs*.xml /product/etc/media_codecs*.xml /apex/com.android.media.swcodec/etc/media_codecs*.xml; do
    if [ -r "${'$'}tvtf_file" ]; then
    echo $CODEC_XML_MARKER; dd if="${'$'}tvtf_file" bs=131072 count=1 2>/dev/null; echo;
    tvtf_count=${'$'}((tvtf_count + 1)); if [ "${'$'}tvtf_count" -ge 24 ]; then break; fi;
    fi; done
""".trimIndent().replace('\n', ' ')

/** Разбираем каждый XML независимо: повреждённый файл не уничтожает соседние результаты. */
internal fun parseDeclaredDecoders(raw: String): Pair<String, String> {
    val decoders = sortedSetOf<Pair<String, String>>(compareBy({ it.first }, { it.second }))
    raw.split(CODEC_XML_MARKER).take(25).forEach { chunk ->
        val xml = chunk.trim()
        val declarations = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        if (xml.isEmpty() || xml.length > 131072 || "<!DOCTYPE" in declarations || "<!ENTITY" in declarations) return@forEach
        val fileDecoders = mutableSetOf<Pair<String, String>>()
        val handler = object : DefaultHandler() {
            var inDecoders = false
            var codec: String? = null
            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                when (qName) {
                    "Decoders" -> inDecoders = true
                    "MediaCodec" -> if (inDecoders) {
                        codec = attrs.getValue("name")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,160}")) }
                        add(attrs.getValue("type"))
                    }
                    "Type" -> if (inDecoders) add(attrs.getValue("name"))
                }
            }
            private fun add(type: String?) {
                val name = codec ?: return
                if (type != null && type.matches(Regex("(?:video|audio)/[A-Za-z0-9_.+-]{1,80}"))) fileDecoders += type to name
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (qName == "MediaCodec") codec = null
                if (qName == "Decoders") inDecoders = false
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("External entity")
            override fun error(e: org.xml.sax.SAXParseException) = throw e
            override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
        }
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(xml)), handler)
            decoders += fileDecoders
        } catch (_: Exception) { /* Неизвестный / усечённый XML не выдаём за список кодеков. */ }
    }
    fun format(prefix: String) = decoders.filter { it.first.startsWith(prefix) }
        .groupBy({ it.first }, { it.second }).entries.joinToString("\n") { (type, names) ->
            "$type: ${names.joinToString(", ")}"
        }
    return format("video/") to format("audio/")
}
