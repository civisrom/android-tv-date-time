package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException
import android.os.Build
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** Реализация поверх Kadb. Вся сетевая работа уходит на Dispatchers.IO. */
class KadbAdbClient(private val kadb: Kadb, private val commandTimeoutMs: Long = 15_000) : AdbClient {
    @Volatile private var closed = false

    override val shellV2Supported: Boolean get() = kadb.supportsFeature("shell_v2")

    override fun openService(destination: String, timeoutMs: Int): AdbService {
        check(!closed) { "ADB client closed" }
        require('\u0000' !in destination && destination.toByteArray(Charsets.UTF_8).size < 4096)
        val stream = kadb.open(destination)
        return object : AdbService {
            override val source = stream.source
            override val sink = stream.sink
            override fun close() = stream.close()
        }
    }

    override fun shell(command: String): ShellResult = boundedAdbCommand(commandTimeoutMs, ::close) {
        check(!closed) { "ADB client closed" }
        val v2 = kadb.supportsFeature("shell_v2")
        val service = if (v2) "shell,v2,raw:$command" else "shell:$command"
        require(service.toByteArray(Charsets.UTF_8).size < 4096) { "ADB command too long" }
        kadb.open(service).use { readBoundedShell(it.source, v2) }
    }

    override fun isAlive(): Boolean = !closed && runCatching { kadb.connectionCheck() }.getOrDefault(false)

    override fun close() {
        closed = true
        runCatching { kadb.close() }
    }
}

class KadbAdbClientFactory(
    private val connectTimeoutMs: Int = 10_000,
    private val socketTimeoutMs: Int = 15_000,
) : AdbClientFactory {
    private val pairing = PairingClient(connectTimeoutMs, socketTimeoutMs, exporter = ::exportAndroidPairingKey)

    /**
     * Открывает соединение и **проверяет его настоящей командой**.
     *
     * `Kadb.create` только запоминает адрес: ни сокета, ни рукопожатия оно не
     * делает и потому не падает никогда — даже на заведомо чужом адресе.
     * Соединение возникает лениво, при первой операции. Без пробы «Подключено»
     * означало бы лишь, что адрес разобран: `connectionCheck()` возвращал бы
     * false, `activeClient` — null, и каждая следующая команда тихо не
     * выполнялась бы.
     *
     * Успех определяется по выводу пробы, а не по тому, что вызов вернулся:
     * в этом проекте статус уже не раз означал не то, чем кажется.
     */
    override fun connect(address: DeviceAddress): AdbClient {
        // Kadb applies this timeout to every transport read, including idle shell output.
        // Individual probes/settings still have their own 15-second boundedAdbCommand deadline.
        val kadb = Kadb.create(address.host, address.port, connectTimeoutMs,
            maxOf(socketTimeoutMs, com.civisrom.tvtimefixer.terminal.TERMINAL_TIMEOUT_MS))
        val client = KadbAdbClient(kadb)
        val response = try {
            client.shell(ADB_PROBE_COMMAND)
        } catch (e: CancellationException) {
            runCatching { kadb.close() }
            throw e
        } catch (e: Exception) {
            runCatching { kadb.close() }
            throw AdbConnectionException(classify(e), e)
        }
        if (response.output.trim() != ADB_PROBE_TOKEN || response.exitCode != 0) {
            runCatching { kadb.close() }
            throw AdbConnectionException(ConnectionError.UNREACHABLE)
        }
        return client
    }

    override suspend fun pair(address: DeviceAddress, pairingCode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED)
        }
        try {
            pairing.pair(address, pairingCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbConnectionException) {
            throw e
        } catch (e: Exception) {
            throw AdbConnectionException(classifyPairingError(e), e)
        }
    }

    private companion object {
        /**
         * Раскладывает исключение на понятную пользователю причину.
         *
         * Разделение не косметическое: «нужно спаривание» и «подтвердите на
         * экране» требуют от человека разных действий, а «не достучались» —
         * третьего. Десктопная половина проекта различает ровно эти же случаи.
         */
        fun classify(error: Throwable): ConnectionError = when (error) {
            is AdbPairAuthException -> ConnectionError.PAIRING_REQUIRED
            is AdbAuthException -> ConnectionError.NOT_AUTHORIZED
            is NoSuchAlgorithmException -> ConnectionError.WIRELESS_UNSUPPORTED
            is SocketTimeoutException -> ConnectionError.CONNECTION_TIMEOUT
            is ConnectException -> ConnectionError.CONNECTION_REFUSED
            is NoRouteToHostException, is UnknownHostException -> ConnectionError.NETWORK_UNAVAILABLE
            is IOException -> ConnectionError.UNREACHABLE
            else -> ConnectionError.UNKNOWN
        }

    }
}

internal fun classifyPairingError(error: Exception): ConnectionError = when (error) {
    is PairingRejectedException, is AdbPairAuthException -> ConnectionError.PAIRING_REJECTED
    is SocketTimeoutException -> ConnectionError.PAIRING_TIMEOUT
    is PairingProtocolException -> ConnectionError.PAIRING_FAILED
    is SSLException -> ConnectionError.TLS_FAILED
    is NoSuchAlgorithmException -> ConnectionError.WIRELESS_UNSUPPORTED
    is ConnectException -> ConnectionError.CONNECTION_REFUSED
    is NoRouteToHostException, is UnknownHostException -> ConnectionError.NETWORK_UNAVAILABLE
    is IOException -> ConnectionError.PAIRING_FAILED
    else -> ConnectionError.UNKNOWN
}
