package com.civisrom.tvtimefixer.adb

import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Реализация поверх Kadb. Вся сетевая работа уходит на Dispatchers.IO. */
class KadbAdbClient(private val kadb: Kadb) : AdbClient {

    override fun shell(command: String): ShellResult {
        val response = kadb.shell(command)
        return ShellResult(
            output = response.output,
            errorOutput = response.errorOutput,
            exitCode = response.exitCode,
        )
    }

    override fun isAlive(): Boolean = runCatching { kadb.connectionCheck() }.getOrDefault(false)

    override fun close() {
        runCatching { kadb.close() }
    }
}

class KadbAdbClientFactory(
    private val connectTimeoutMs: Int = 10_000,
    private val socketTimeoutMs: Int = 15_000,
) : AdbClientFactory {

    override fun connect(address: DeviceAddress): AdbClient =
        try {
            KadbAdbClient(
                Kadb.create(address.host, address.port, connectTimeoutMs, socketTimeoutMs)
            )
        } catch (e: Throwable) {
            throw AdbConnectionException(classify(e), e)
        }

    override suspend fun pair(address: DeviceAddress, pairingCode: String) {
        withContext(Dispatchers.IO) {
            try {
                Kadb.pair(address.host, address.port, pairingCode)
            } catch (e: Throwable) {
                throw AdbConnectionException(classifyPairing(e), e)
            }
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
            is SocketTimeoutException,
            is ConnectException,
            is NoRouteToHostException,
            is UnknownHostException,
            -> ConnectionError.UNREACHABLE
            is IOException -> ConnectionError.UNREACHABLE
            else -> ConnectionError.UNKNOWN
        }

        fun classifyPairing(error: Throwable): ConnectionError = when (error) {
            is AdbPairAuthException -> ConnectionError.PAIRING_REJECTED
            else -> classify(error)
        }
    }
}
