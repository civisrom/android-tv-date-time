@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.core.AdbConnection
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
internal class KadbAdbClient(
    private val connection: AdbConnection,
    private val session: AdbConnectSession,
    private val commandTimeoutMs: Long = 15_000,
) : AdbClient {
    @Volatile private var closed = false

    override val shellV2Supported: Boolean get() = connection.supportsFeature("shell_v2")

    override fun openService(destination: String, timeoutMs: Int): AdbService {
        check(!closed) { "ADB client closed" }
        require('\u0000' !in destination && destination.toByteArray(Charsets.UTF_8).size < 4096)
        val stream = connection.open(destination)
        return object : AdbService {
            override val source = stream.source
            override val sink = stream.sink
            override fun close() = stream.close()
        }
    }

    override fun shell(command: String): ShellResult = boundedAdbCommand(commandTimeoutMs, ::close) {
        check(!closed) { "ADB client closed" }
        val v2 = connection.supportsFeature("shell_v2")
        val service = if (v2) "shell,v2,raw:$command" else "shell:$command"
        require(service.toByteArray(Charsets.UTF_8).size < 4096) { "ADB command too long" }
        connection.open(service).use { readBoundedShell(it.source, v2) }
    }

    override fun isAlive(): Boolean = !closed && session.isOpen

    override fun close() {
        closed = true
        session.close()
        runCatching { connection.close() }
    }
}

class KadbAdbClientFactory(
    private val connectTimeoutMs: Int = 10_000,
    private val socketTimeoutMs: Int = 15_000,
) : AdbClientFactory {
    private val pairing = PairingClient(connectTimeoutMs, socketTimeoutMs, exporter = ::exportAndroidPairingKey)

    /** Соединение считается установленным только после AUTH/TLS и успешной shell-пробы. */
    override fun connect(address: DeviceAddress): AdbClient {
        // AUTH/TLS has its own overall deadline. Terminal reads may remain silent for longer.
        val session = AdbConnectSession(address.host, address.port, connectTimeoutMs,
            maxOf(socketTimeoutMs, com.civisrom.tvtimefixer.terminal.TERMINAL_TIMEOUT_MS))
        return try {
            cancellableAdbConnect(60_000, session::close) {
                val client = session.connect()
                try {
                    val response = client.shell(ADB_PROBE_COMMAND)
                    if (response.output.trim() != ADB_PROBE_TOKEN || response.exitCode != 0) {
                        throw AdbConnectionException(ConnectionError.UNREACHABLE)
                    }
                    client
                } catch (error: Exception) {
                    client.close()
                    throw error
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbConnectionException) {
            throw e
        } catch (e: Exception) {
            throw AdbConnectionException(classify(e), e)
        }
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

    internal companion object {
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
            is SSLException -> ConnectionError.TLS_FAILED
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
