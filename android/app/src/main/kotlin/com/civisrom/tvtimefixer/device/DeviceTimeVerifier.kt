package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.data.isValidNtpServer
import com.civisrom.tvtimefixer.net.SntpQuery
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

enum class DeviceTimeStatus { MATCH, MISMATCH, UNCERTAIN, NO_SERVER, NTP_UNAVAILABLE, DEVICE_UNAVAILABLE }

/** Разовый замер часов, а не подтверждение источника последней синхронизации Android. */
data class DeviceTimeCheck(
    val status: DeviceTimeStatus,
    val server: String = "",
    val deviceTimeMillis: Long? = null,
    val differenceSeconds: Double? = null,
    val uncertaintySeconds: Double? = null,
    val automaticTime: Boolean? = null,
)

/** Только чтение через ADB и один NTP-запрос. Вызывается на Dispatchers.IO. */
class DeviceTimeVerifier(
    private val query: SntpQuery,
    private val elapsedRealtime: () -> Long,
) {
    fun verify(client: AdbClient): DeviceTimeCheck {
        val server = read(client, "settings get global ntp_server")
            ?: return DeviceTimeCheck(DeviceTimeStatus.DEVICE_UNAVAILABLE)
        val automatic = when (read(client, "settings get global auto_time")) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val base = DeviceTimeCheck(DeviceTimeStatus.NO_SERVER, automaticTime = automatic)
        if (!isValidNtpServer(server)) return base
        val result = base.copy(server = server)
        val network = try {
            query.query(server)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return result.copy(status = DeviceTimeStatus.NTP_UNAVAILABLE)
        }
        val reference = network.referenceTimeMillis
            ?: return result.copy(status = DeviceTimeStatus.NTP_UNAVAILABLE)
        val referenceElapsed = network.referenceElapsedMillis
            ?: return result.copy(status = DeviceTimeStatus.NTP_UNAVAILABLE)
        val started = elapsedRealtime()
        val seconds = read(client, "date +%s")?.toLongOrNull()
        val finished = elapsedRealtime()
        if (seconds == null || seconds !in 0L..253_402_300_799L) {
            return result.copy(status = DeviceTimeStatus.DEVICE_UNAVAILABLE)
        }
        val measured = result.copy(deviceTimeMillis = seconds * 1000L)
        if (reference <= 0 || network.rttMs < 0 || started < referenceElapsed || finished < started ||
            finished - referenceElapsed > 30_000L) {
            return measured.copy(status = DeviceTimeStatus.UNCERTAIN)
        }

        // date +%s округляет вниз. Момент чтения лежит между отправкой команды и ответом.
        // Используем середины интервалов и учитываем погрешность обоих обменов.
        val duration = finished - started
        val networkAtRead = reference.toDouble() + (started - referenceElapsed) + duration / 2.0
        val difference = seconds * 1000.0 + 500.0 - networkAtRead
        // Ещё 2 мс покрывают округление дробных NTP-меток и сетевого ориентира.
        val uncertainty = 502.0 + duration / 2.0 + network.rttMs / 2.0
        val status = when {
            abs(difference) + uncertainty <= 5_000.0 -> DeviceTimeStatus.MATCH
            abs(difference) - uncertainty > 5_000.0 -> DeviceTimeStatus.MISMATCH
            else -> DeviceTimeStatus.UNCERTAIN
        }
        return measured.copy(status = status, differenceSeconds = difference / 1000.0,
            uncertaintySeconds = uncertainty / 1000.0)
    }

    private fun read(client: AdbClient, command: String): String? = try {
        val response = client.shell(command)
        response.trimmedOutput.takeIf { response.exitCode == 0 && response.errorOutput.isBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
