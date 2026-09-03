package com.civisrom.tvtimefixer.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Ответ сервера времени: сколько шёл обмен и насколько часы устройства расходятся. */
data class SntpResult(val rttMs: Long, val offsetSeconds: Double)

/**
 * Запрос к серверу времени.
 *
 * Отделено интерфейсом по той же причине, что и [com.civisrom.tvtimefixer.adb.AdbClient]:
 * всё, что вокруг — подсчёт доли успешных ответов, отбраковка, сортировка —
 * должно проверяться на JVM без сети.
 */
interface SntpQuery {
    /** Бросает исключение, если сервер не ответил или ответил не как NTP. */
    fun query(host: String): SntpResult
}

/** Ответ пришёл, но это не ответ NTP-сервера. */
class NotAnNtpServerException(message: String) : Exception(message)

/**
 * Сборка и разбор пакета SNTP (RFC 4330).
 *
 * Вынесено отдельно от сокета намеренно: именно здесь решается, отвечает ли
 * адрес **как сервер времени**, а не просто «порт открыт». Случайная служба на
 * UDP/123, отражающая байты, не даст ни режима 4, ни правдоподобного stratum,
 * ни ненулевой метки передачи — и будет отвергнута.
 */
object SntpPacket {

    const val SIZE = 48
    const val PORT = 123

    /** Разница между эпохой NTP (1900) и эпохой Unix (1970), в секундах. */
    private const val EPOCH_OFFSET_SECONDS = 2_208_988_800L

    private const val MODE_CLIENT = 3
    private const val MODE_SERVER = 4
    private const val VERSION = 3

    private const val INDEX_ORIGINATE = 24
    private const val INDEX_RECEIVE = 32
    private const val INDEX_TRANSMIT = 40

    /** Запрос клиента: LI = 0, VN = 3, Mode = 3, остальное нули. */
    fun request(): ByteArray = ByteArray(SIZE).also {
        it[0] = ((VERSION shl 3) or MODE_CLIENT).toByte()
    }

    /**
     * Разбирает ответ. Возвращает null, если это не ответ сервера времени.
     *
     * @param t1 момент отправки запроса, мс Unix
     * @param t4 момент получения ответа, мс Unix
     */
    fun parse(response: ByteArray, t1: Long, t4: Long): SntpResult? {
        if (response.size < SIZE) return null

        val mode = response[0].toInt() and 0x07
        if (mode != MODE_SERVER) return null

        // Ноль — Kiss-o'-Death: сервер отвечает, но обслуживать отказывается.
        // Всё, что выше 15, протоколом не определено.
        val stratum = response[1].toInt() and 0xFF
        if (stratum !in 1..15) return null

        // Обе метки участвуют в расчёте смещения, поэтому нулевая делает ответ
        // бесполезным: настоящий сервер заполняет обе
        val t2 = readTimestamp(response, INDEX_RECEIVE)
        val t3 = readTimestamp(response, INDEX_TRANSMIT)
        if (t2 == 0L || t3 == 0L) return null

        // RFC 4330: смещение = ((t2 - t1) + (t3 - t4)) / 2,
        // задержка = (t4 - t1) - (t3 - t2)
        val offsetMs = ((t2 - t1) + (t3 - t4)) / 2.0
        val rttMs = (t4 - t1) - (t3 - t2)
        return SntpResult(rttMs = rttMs.coerceAtLeast(0L), offsetSeconds = offsetMs / 1000.0)
    }

    /** 64-битная метка времени NTP по смещению в пакете — в миллисекунды Unix. */
    private fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        var seconds = 0L
        for (i in 0 until 4) {
            seconds = (seconds shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        var fraction = 0L
        for (i in 4 until 8) {
            fraction = (fraction shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        if (seconds == 0L && fraction == 0L) return 0L
        return (seconds - EPOCH_OFFSET_SECONDS) * 1000L + (fraction * 1000L) / 0x100000000L
    }
}

/** Настоящий клиент поверх UDP. Вся сетевая работа вызывающего — на Dispatchers.IO. */
class UdpSntpClient(private val timeoutMs: Int = 2_000) : SntpQuery {

    override fun query(host: String): SntpResult {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val out = SntpPacket.request()
            val t1 = System.currentTimeMillis()
            socket.send(DatagramPacket(out, out.size, address, SntpPacket.PORT))

            val buffer = ByteArray(SntpPacket.SIZE)
            val incoming = DatagramPacket(buffer, buffer.size)
            socket.receive(incoming)
            val t4 = System.currentTimeMillis()

            return SntpPacket.parse(buffer.copyOf(incoming.length), t1, t4)
                ?: throw NotAnNtpServerException("$host отвечает, но не по протоколу NTP")
        }
    }
}
