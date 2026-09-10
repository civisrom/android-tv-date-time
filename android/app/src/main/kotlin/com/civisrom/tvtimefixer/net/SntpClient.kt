package com.civisrom.tvtimefixer.net

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlin.math.round

/**
 * Ответ сервера времени: сколько шёл обмен и насколько часы устройства
 * расходятся.
 *
 * [address] — IP, к которому обращались на самом деле. Отдельного запроса DNS
 * для него не нужно: имя всё равно разрешается перед отправкой пакета.
 */
data class SntpResult(
    val rttMs: Long,
    val offsetSeconds: Double,
    val address: String = "",
    /** Сетевое время на момент [referenceElapsedMillis], независимо от часов телефона. */
    val referenceTimeMillis: Long? = null,
    val referenceElapsedMillis: Long? = null,
)

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
    fun query(host: String, checkCancelled: () -> Unit): SntpResult {
        checkCancelled()
        return query(host).also { checkCancelled() }
    }
    fun query(host: String, port: Int, checkCancelled: () -> Unit = {}): SntpResult {
        require(port == 123) { "This NTP client does not support custom ports" }
        return query(host, checkCancelled)
    }
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

    /** Запрос клиента: LI = 0, VN = 3, Mode = 3; метка передачи связывает ответ с запросом. */
    fun request(transmitTimeMillis: Long? = null): ByteArray = ByteArray(SIZE).also {
        it[0] = ((VERSION shl 3) or MODE_CLIENT).toByte()
        if (transmitTimeMillis != null) writeTimestamp(it, INDEX_TRANSMIT, transmitTimeMillis)
    }

    /**
     * Разбирает ответ. Возвращает null, если это не ответ сервера времени.
     *
     * @param t1 момент отправки запроса, мс Unix
     * @param t4 момент получения ответа, мс Unix
     */
    fun parse(response: ByteArray, t1: Long, t4: Long, request: ByteArray? = null): SntpResult? {
        if (response.size < SIZE) return null

        val mode = response[0].toInt() and 0x07
        if (mode != MODE_SERVER) return null
        val version = (response[0].toInt() ushr 3) and 0x07
        val leap = (response[0].toInt() ushr 6) and 0x03
        if (version !in 3..4 || leap == 3 || t4 < t1) return null
        if (request != null && (request.size != SIZE ||
                !(0 until 8).all { response[INDEX_ORIGINATE + it] == request[INDEX_TRANSMIT + it] })) return null

        // Ноль — Kiss-o'-Death: сервер отвечает, но обслуживать отказывается.
        // Всё, что выше 15, протоколом не определено.
        val stratum = response[1].toInt() and 0xFF
        if (stratum !in 1..15) return null

        // Обе метки участвуют в расчёте смещения, поэтому нулевая делает ответ
        // бесполезным: настоящий сервер заполняет обе
        val t2 = readTimestamp(response, INDEX_RECEIVE, t1) ?: return null
        val t3 = readTimestamp(response, INDEX_TRANSMIT, t2) ?: return null
        if (t3 < t2) return null

        // RFC 4330: смещение = ((t2 - t1) + (t3 - t4)) / 2,
        // задержка = (t4 - t1) - (t3 - t2)
        val offsetMs = ((t2 - t1) + (t3 - t4)) / 2.0
        val rttMs = (t4 - t1) - (t3 - t2)
        if (rttMs < -1L) return null // До 1 мс может потеряться при округлении меток.
        return SntpResult(rttMs = rttMs.coerceAtLeast(0L), offsetSeconds = offsetMs / 1000.0,
            referenceTimeMillis = t4 + offsetMs.toLong())
    }

    private fun writeTimestamp(buffer: ByteArray, offset: Int, unixMs: Long) {
        val millis = ((unixMs % 1000L) + 1000L) % 1000L
        val seconds = (unixMs - millis) / 1000L + EPOCH_OFFSET_SECONDS
        val fraction = millis * 0x100000000L / 1000L
        for (i in 0 until 4) {
            buffer[offset + i] = (seconds ushr (24 - 8 * i)).toByte()
            buffer[offset + 4 + i] = (fraction ushr (24 - 8 * i)).toByte()
        }
    }

    /** 64-битная метка времени NTP по смещению в пакете — в миллисекунды Unix. */
    private fun readTimestamp(buffer: ByteArray, offset: Int, referenceMillis: Long): Long? {
        var seconds = 0L
        for (i in 0 until 4) {
            seconds = (seconds shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        var fraction = 0L
        for (i in 4 until 8) {
            fraction = (fraction shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        if (seconds == 0L && fraction == 0L) return null
        // RFC 5905: для восстановления эпохи нужен ориентир с точностью ±68 лет.
        val referenceSeconds = referenceMillis / 1000L + EPOCH_OFFSET_SECONDS
        seconds += round((referenceSeconds - seconds) / 4294967296.0).toLong() * 0x100000000L
        return (seconds - EPOCH_OFFSET_SECONDS) * 1000L + (fraction * 1000L) / 0x100000000L
    }
}

/** Настоящий клиент поверх UDP. Вся сетевая работа вызывающего — на Dispatchers.IO. */
class UdpSntpClient(
    private val timeoutMs: Int = 2_000,
    private val port: Int = SntpPacket.PORT,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : SntpQuery {

    override fun query(host: String): SntpResult = query(host, port) { }

    override fun query(host: String, checkCancelled: () -> Unit): SntpResult = query(host, port, checkCancelled)

    override fun query(host: String, port: Int, checkCancelled: () -> Unit): SntpResult {
        val deadline = elapsedRealtime() + timeoutMs
        fun remaining(until: Long = deadline): Int {
            checkCancelled()
            if (Thread.currentThread().isInterrupted) throw CancellationException("NTP cancelled")
            return (until - elapsedRealtime()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                .also { if (it <= 0) throw SocketTimeoutException("NTP deadline exceeded") }
        }
        val lookup = try { dns.submit<Array<InetAddress>> { resolve(host) } }
            catch (_: RejectedExecutionException) { throw SocketTimeoutException("DNS resolver busy") }
        val resolved = try {
            var result: Array<InetAddress>? = null
            while (result == null) {
                try { result = lookup.get(remaining().coerceAtMost(100).toLong(), TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { remaining() }
            }
            result.distinct().sortedBy { it !is Inet4Address }
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception ?: e)
        } finally {
            lookup.cancel(true)
            dns.purge()
        }
        var lastError: Exception = java.net.UnknownHostException(host)
        for ((index, address) in resolved.withIndex()) {
            val addressDeadline = elapsedRealtime() + remaining() / (resolved.size - index)
            try {
                DatagramSocket().use { socket ->
                    // Принимаем ответ только от выбранного адреса и UDP-порта.
                    socket.connect(address, port)
                    val t1 = System.currentTimeMillis()
                    val started = elapsedRealtime()
                    val out = SntpPacket.request(t1)
                    socket.send(DatagramPacket(out, out.size, address, port))

                    val buffer = ByteArray(SntpPacket.SIZE)
                    val incoming = DatagramPacket(buffer, buffer.size)
                    while (true) {
                        socket.soTimeout = remaining(addressDeadline).coerceAtMost(100)
                        try { socket.receive(incoming); break }
                        catch (_: SocketTimeoutException) { remaining(addressDeadline) }
                    }
                    val received = elapsedRealtime()
                    // Автокоррекция часов телефона во время запроса не меняет длительность обмена.
                    val t4 = t1 + (received - started)

                    val parsed = SntpPacket.parse(buffer.copyOf(incoming.length), t1, t4, out)
                        ?: throw NotAnNtpServerException("$host отвечает, но не по протоколу NTP")
                    return parsed.copy(address = address.hostAddress.orEmpty(), referenceElapsedMillis = received)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { throw CancellationException("NTP cancelled", e) }
            catch (e: Exception) { lastError = e }
        }
        throw lastError
    }

    private companion object {
        // Системный DNS иногда игнорирует interrupt; ограничиваем и потоки, и очередь.
        val dns = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
            { task -> Thread(task, "ntp-dns").apply { isDaemon = true } })
    }
}
