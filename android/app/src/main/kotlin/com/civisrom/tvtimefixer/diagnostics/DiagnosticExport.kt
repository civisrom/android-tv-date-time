package com.civisrom.tvtimefixer.diagnostics

import com.civisrom.tvtimefixer.device.ClockMonitorState
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import java.util.Locale

data class DiagnosticExportState(
    val appVersion: String,
    val controllerApi: Int,
    val targetApi: Int? = null,
    val transport: DiagnosticTransport = DiagnosticTransport.NONE,
    val connected: Boolean = false,
    val settingVerified: Boolean? = null,
    val clock: DeviceTimeCheck? = null,
    val snapshotSaved: Boolean = false,
    val profileCount: Int = 0,
    val monitor: ClockMonitorState = ClockMonitorState(),
    val elapsedNow: Long = 0,
)

/** Explicit allowlist. Never serialize AppState, exception details, raw commands, addresses or identity hashes. */
fun diagnosticExport(state: DiagnosticExportState, journal: DiagnosticSnapshot): String = buildString {
    appendLine("TVTimeFixer diagnostic report v1")
    appendLine("privacy=addresses_and_device_identifiers_omitted")
    appendLine("app_version=" + state.appVersion.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?")) }.orEmpty())
    appendLine("controller_api=" + state.controllerApi.takeIf { it in 1..99 })
    appendLine("target_api=" + state.targetApi?.takeIf { it in 1..99 })
    appendLine("transport=${state.transport}; connected=${state.connected}")
    appendLine("ntp_setting_readback=${state.settingVerified ?: "unknown"}")
    appendLine("clock_last_comparison=${state.clock?.status ?: "unknown"}")
    appendLine("clock_comparison=" + when { state.clock == null -> "unknown"; state.clock.isFresh(state.elapsedNow) -> state.clock.status.name; else -> "STALE" })
    appendLine("clock_measurement_age_ms=${state.clock?.ageMillis(state.elapsedNow) ?: "unknown"}")
    state.clock?.differenceSeconds?.takeIf(Double::isFinite)?.let {
        appendLine("clock_difference_seconds=" + String.format(Locale.ROOT, "%.3f", it))
    }
    state.clock?.uncertaintySeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
        appendLine("clock_uncertainty_seconds=" + String.format(Locale.ROOT, "%.3f", it))
    }
    appendLine("clock_source_current=unconfirmed")
    appendLine("snapshot_saved=${state.snapshotSaved}; profiles=${state.profileCount.coerceIn(0, 64)}")
    appendLine("monitor_running=${state.monitor.running}; samples=${state.monitor.samples.size.coerceAtMost(20)}")
    appendLine("monitor_skipped=${state.monitor.samples.count { it.skipped != null }.coerceAtMost(20)}")
    state.monitor.ended?.let { appendLine("monitor_end=$it") }
    appendLine("journal_storage=${journal.storageAvailable}; dropped=${journal.dropped.coerceAtLeast(0)}")
    journal.events.takeLast(100).forEachIndexed { index, event ->
        append("event=${index + 1}; operation=${event.operation}; outcome=${event.outcome}; transport=${event.transport}")
        append("; duration_ms=${event.durationMs.coerceIn(0, 3_600_000)}")
        event.reason?.let { append("; reason=$it") }
        event.issue?.let { append("; issue=$it") }
        appendLine()
    }
}
