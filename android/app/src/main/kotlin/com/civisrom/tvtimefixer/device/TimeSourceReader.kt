package com.civisrom.tvtimefixer.device

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.ShellResult
import kotlinx.coroutines.CancellationException

enum class TimeSourceReadStatus { AVAILABLE, PERMISSION_DENIED, UNSUPPORTED, UNAVAILABLE, UNPARSEABLE }
enum class RecordedClockOrigin { NETWORK, TELEPHONY, GNSS, EXTERNAL, MANUAL }
data class TimeSourceEvidence(
    val networkStatus: TimeSourceReadStatus = TimeSourceReadStatus.UNAVAILABLE,
    val detectorStatus: TimeSourceReadStatus = TimeSourceReadStatus.UNAVAILABLE,
    val lastResponseUri: String? = null,
    val responseAgeMillis: Long? = null,
    val lastRecordedClockOrigin: RecordedClockOrigin? = null,
)

/** Historical service evidence, never a claim that the currently displayed time came from this URI. */
class TimeSourceReader(private val client: AdbClient) {
    fun read(): TimeSourceEvidence = parseTimeSourceEvidence(
        read("dumpsys network_time_update_service"), read("dumpsys time_detector"))

    private fun read(command: String): ShellResult? = try {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Time source read cancelled")
        client.shell(command).takeIf { it.output.length + it.errorOutput.length <= 131_072 }
    } catch (error: CancellationException) { throw error
    } catch (error: InterruptedException) { throw CancellationException("Time source read cancelled", error)
    } catch (_: Exception) { null }
}

internal fun parseTimeSourceEvidence(network: ShellResult?, detector: ShellResult?): TimeSourceEvidence {
    fun status(result: ShellResult?, recognizable: Boolean): TimeSourceReadStatus = when {
        result == null -> TimeSourceReadStatus.UNAVAILABLE
        result.permissionDenied -> TimeSourceReadStatus.PERMISSION_DENIED
        "Can't find service" in result.output || "Can't find service" in result.errorOutput -> TimeSourceReadStatus.UNSUPPORTED
        result.exitCode != 0 || result.errorOutput.isNotBlank() -> TimeSourceReadStatus.UNAVAILABLE
        !recognizable -> TimeSourceReadStatus.UNPARSEABLE
        else -> TimeSourceReadStatus.AVAILABLE
    }
    val netText = network?.output.orEmpty()
    val detectorText = detector?.output.orEmpty()
    val netStatus = status(network, "NtpTrustedTime:" in netText || "mTimeResult=" in netText)
    val detectorStatus = status(detector, "mLastAutoSystemClockTimeSet=" in detectorText)
    val uri = if (netStatus == TimeSourceReadStatus.AVAILABLE) {
        Regex("(?m)^\\s*mLastSuccessfulNtpServerUri=(\\S+)\\s*$").find(netText)?.groupValues?.get(1)?.takeIf {
            it.startsWith("ntp://") && '|' !in it && NtpConfiguration(it).endpoints.size == 1
        }
    } else null
    val age = if (uri != null) Regex("(?m)^\\s*mTimeResult\\.getAgeMillis\\(\\)=([^\\r\\n]+)")
        .find(netText)?.groupValues?.get(1)?.trim()?.let(::parseAgeMillis) else null
    // Match the actual clock-write event, not a confidence-only update or a network suggestion.
    val origin = if (detectorStatus == TimeSourceReadStatus.AVAILABLE) {
        Regex("(?m)^\\s*PT[0-9.HMS]+ / [^\\r\\n]+ - Set system clock & confidence\\. origin=([^\\s,]+)(?:\\s|,)")
            .findAll(detectorText).lastOrNull()?.groupValues?.get(1)?.uppercase()?.let { lastOrigin ->
                RecordedClockOrigin.entries.firstOrNull { it.name == lastOrigin }
            }
    } else null
    return TimeSourceEvidence(netStatus, detectorStatus, uri, age, origin)
}

private fun parseAgeMillis(value: String): Long? {
    value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
    val match = Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matchEntire(value) ?: return null
    if (match.groupValues.drop(1).all(String::isEmpty)) return null
    val factors = listOf(3_600_000.0, 60_000.0, 1_000.0)
    val millis = match.groupValues.drop(1).mapIndexed { i, part -> (part.toDoubleOrNull() ?: 0.0) * factors[i] }.sum()
    return millis.takeIf { it.isFinite() && it in 0.0..Long.MAX_VALUE.toDouble() }?.toLong()
}
