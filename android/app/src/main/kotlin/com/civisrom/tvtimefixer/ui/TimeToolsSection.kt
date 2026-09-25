package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.civisrom.tvtimefixer.DeviceMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.data.SavedTimeSettings
import com.civisrom.tvtimefixer.data.TimeProfile
import com.civisrom.tvtimefixer.device.*
import java.text.DateFormat
import java.util.Date

/** Device-bound data is cleared on disconnect; measurements are never persisted. */
data class TimeToolsState(
    val identity: TimeDeviceIdentity? = null,
    val snapshot: SavedTimeSettings? = null,
    val profiles: List<TimeProfile> = emptyList(),
    val current: TimeSettingsRead? = null,
    val change: TimeSettingsChange? = null,
    val source: TimeSourceEvidence? = null,
    val monitor: ClockMonitorState = ClockMonitorState(),
    val notice: Int? = null,
    val lastOperationSucceeded: Boolean? = null,
)

internal fun TimeSetting.label(): Int = when (this) {
    TimeSetting.NTP -> R.string.time_field_ntp
    TimeSetting.AUTO_TIME -> R.string.time_field_auto
    TimeSetting.AUTO_TIME_ZONE -> R.string.time_field_auto_zone
    TimeSetting.TIME_ZONE -> R.string.time_field_zone
}
internal fun TimeSettingStatus.label(): Int = when (this) {
    TimeSettingStatus.VERIFIED -> R.string.time_status_verified
    TimeSettingStatus.UNAVAILABLE -> R.string.time_status_unavailable
    TimeSettingStatus.PERMISSION_DENIED -> R.string.time_status_permission
    TimeSettingStatus.UNSUPPORTED -> R.string.time_status_unsupported
    TimeSettingStatus.NOT_CONFIRMED -> R.string.time_status_unconfirmed
    TimeSettingStatus.FAILED -> R.string.time_status_failed
    TimeSettingStatus.INVALID -> R.string.time_status_invalid
}

@Composable internal fun settingValueText(field: TimeSetting, value: String?): String = when {
    value == null -> stringResource(R.string.time_status_unavailable)
    value == "null" -> stringResource(R.string.time_value_absent)
    value == "" -> stringResource(R.string.time_value_empty)
    field in setOf(TimeSetting.AUTO_TIME, TimeSetting.AUTO_TIME_ZONE) && value == "1" -> stringResource(R.string.time_value_on)
    field in setOf(TimeSetting.AUTO_TIME, TimeSetting.AUTO_TIME_ZONE) && value == "0" -> stringResource(R.string.time_value_off)
    field == TimeSetting.NTP -> value.split('|').joinToString(", ") { it.removePrefix("ntp://") }
    else -> value
}

@Composable private fun SettingValues(settings: TimeSettings, before: TimeSettings? = null) {
    TimeSetting.entries.forEach { field ->
        val value = settingValueText(field, settings[field])
        Text(stringResource(field.label()) + ": " + if (before == null) value else
            stringResource(R.string.time_value_change, settingValueText(field, before[field]), value))
    }
    Text(stringResource(R.string.time_effective_auto_zone, when (settings.effectiveAutoZone) {
        true -> stringResource(R.string.time_effective_on)
        false -> stringResource(R.string.time_effective_off)
        null -> stringResource(R.string.time_status_unavailable)
    }))
}

/** Preview is local UI state: cancelling it cannot call a mutation action. */
@Composable internal fun TimeToolsSection(state: AppState, actions: AppActions, mode: DeviceMode = DeviceMode.HANDHELD) {
    val editorNavigation = tvTextFieldNavigation(mode)
    val tools = state.timeTools
    val enabled = state.connected && !state.busy
    var profileName by rememberSaveable(tools.identity) { mutableStateOf("") }
    var servers by rememberSaveable(tools.identity) { mutableStateOf("") }
    var preview by remember(tools.identity) { mutableStateOf<String?>(null) }
    var selectedProfile by remember(tools.identity) { mutableStateOf<TimeProfile?>(null) }
    val hosts = servers.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val api = state.deviceInfo?.apiLevel?.toIntOrNull() ?: tools.current?.apiLevel
    val formatted = ntpSettingForHosts(hosts, api)
    val bound = tools.identity != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("time-tools-content")) {
        Text(stringResource(R.string.time_tools_hint), style = MaterialTheme.typography.bodySmall)
        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("time-tools-progress"))
            state.operation?.let { Text(stringResource(it.labelRes())) }
        }
        FilledTonalButton(onClick = actions::refreshTimeTools, enabled = enabled,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-tools-refresh")) { Text(stringResource(R.string.time_tools_refresh)) }
        if (!bound && state.connected) Text(stringResource(R.string.time_identity_unavailable))
        tools.notice?.let { Text(stringResource(it), modifier = Modifier.testTag("time-tools-notice")) }
        tools.current?.let { current ->
            Text(stringResource(R.string.time_current_title), style = MaterialTheme.typography.titleSmall)
            SettingValues(current.settings)
            current.statuses.filterValues { it != TimeSettingStatus.VERIFIED }.forEach { (field, status) ->
                Text(stringResource(field.label()) + ": " + stringResource(status.label()))
            }
        }
        tools.change?.let { change ->
            if (!change.identityMatches) Text(stringResource(R.string.time_identity_mismatch))
            Text(stringResource(if (change.confirmed) R.string.time_settings_confirmed else R.string.time_settings_partial))
            change.statuses.forEach { (field, status) -> Text(stringResource(field.label()) + ": " + stringResource(status.label())) }
            change.rollback?.let { rollback ->
                Text(stringResource(R.string.time_rollback_result))
                rollback.forEach { (field, status) -> Text(stringResource(field.label()) + ": " + stringResource(status.label())) }
            }
        }
        Text(stringResource(R.string.time_snapshot_hint), style = MaterialTheme.typography.bodySmall)
        tools.snapshot?.let { Text(stringResource(R.string.time_snapshot_date,
            DateFormat.getDateTimeInstance().format(Date(it.capturedAt)))) }
            ?: Text(stringResource(R.string.time_snapshot_none))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { preview = "snapshot" }, enabled = enabled && bound,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-snapshot-save")) {
                Text(stringResource(if (tools.snapshot == null) R.string.time_snapshot_save else R.string.time_snapshot_replace))
            }
            FilledTonalButton(onClick = { preview = "restore" }, enabled = enabled && tools.snapshot != null,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-snapshot-restore")) { Text(stringResource(R.string.time_snapshot_restore)) }
        }
        Text(stringResource(R.string.time_profiles_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.time_profiles_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = profileName, onValueChange = { if (it.length <= 80 && it.none(Char::isISOControl)) profileName = it },
            enabled = enabled && bound, label = { Text(stringResource(R.string.time_profile_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().then(editorNavigation).testTag("time-profile-name"))
        FilledTonalButton(onClick = { preview = "save-profile" },
            enabled = enabled && bound && profileName.isNotBlank() && tools.profiles.none { it.name == profileName.trim() },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-profile-save")) { Text(stringResource(R.string.time_profile_save)) }
        tools.profiles.forEachIndexed { index, profile ->
            Text(profile.name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selectedProfile = profile; preview = "apply-profile" }, enabled = enabled,
                    modifier = Modifier.testTag("time-profile-apply-$index")) { Text(stringResource(R.string.time_profile_apply)) }
                TextButton(onClick = { selectedProfile = profile; preview = "delete-profile" }, enabled = enabled,
                    modifier = Modifier.testTag("time-profile-delete-$index")) { Text(stringResource(R.string.time_profile_delete)) }
            }
        }
        Text(stringResource(R.string.time_ntp_list_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(if ((api ?: 0) >= 34) R.string.time_ntp_list_hint else R.string.time_ntp_single_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = servers, onValueChange = { if (it.length <= 1100) servers = it }, enabled = enabled && bound,
            label = { Text(stringResource(R.string.time_ntp_list_input)) }, maxLines = 4,
            modifier = Modifier.fillMaxWidth().then(editorNavigation).testTag("time-ntp-list"))
        if (servers.isNotBlank() && formatted == null) Text(stringResource(R.string.time_ntp_list_invalid))
        FilledTonalButton(onClick = { preview = "ntp" }, enabled = enabled && bound && formatted != null,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-ntp-list-apply")) { Text(stringResource(R.string.time_profile_apply)) }
        Text(stringResource(R.string.time_source_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.time_source_unconfirmed))
        tools.source?.let { source ->
            Text(stringResource(R.string.time_source_service_status, source.networkStatus.localized(), source.detectorStatus.localized()))
            source.lastResponseUri?.let { Text(stringResource(R.string.time_source_response, it)) }
            source.responseAgeMillis?.let { Text(stringResource(R.string.time_source_age, it / 1000)) }
            source.lastRecordedClockOrigin?.let { Text(stringResource(R.string.time_source_recorded, it.name.lowercase())) }
        }
        Text(stringResource(R.string.time_monitor_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.time_monitor_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = actions::startClockMonitor, enabled = enabled && !tools.monitor.running,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-monitor-start")) { Text(stringResource(R.string.time_monitor_start)) }
            FilledTonalButton(onClick = actions::stopClockMonitor, enabled = tools.monitor.running,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("time-monitor-stop")) { Text(stringResource(R.string.time_monitor_stop)) }
        }
        Text(stringResource(if (tools.monitor.running) R.string.time_monitor_running else R.string.time_monitor_stopped), modifier = Modifier.testTag("time-monitor-status"))
        tools.monitor.ended?.let { Text(stringResource(when (it) {
            ClockMonitorEnd.USER -> R.string.time_monitor_user
            ClockMonitorEnd.DURATION -> R.string.time_monitor_duration
            ClockMonitorEnd.BACKGROUND -> R.string.time_monitor_background
            ClockMonitorEnd.DISCONNECTED -> R.string.time_monitor_disconnected
        })) }
        tools.monitor.samples.forEachIndexed { index, sample ->
            val check = sample.check
            Text(stringResource(R.string.time_monitor_sample, sample.elapsedMs / 1000,
                when (sample.skipped) {
                    ClockSampleSkip.BUSY -> stringResource(R.string.time_monitor_busy)
                    ClockSampleSkip.UNAVAILABLE -> stringResource(R.string.time_status_unavailable)
                    null -> check?.let { stringResource(it.status.messageRes()) }.orEmpty()
                }), modifier = Modifier.testTag("time-monitor-sample-$index"))
            if (check?.differenceSeconds != null && check.uncertaintySeconds != null)
                Text(stringResource(R.string.time_monitor_offset, check.differenceSeconds, check.uncertaintySeconds))
        }
    }
    preview?.let { kind ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(stringResource(R.string.time_preview_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("time-preview-values"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (kind) {
                    "snapshot" -> R.string.time_snapshot_preview
                    "save-profile" -> R.string.time_profile_save_preview
                    "delete-profile" -> R.string.time_profile_delete_preview
                    "ntp" -> R.string.time_ntp_preview
                    else -> R.string.time_restore_preview
                }))
                when (kind) {
                    "restore" -> tools.snapshot?.let { SettingValues(it.settings, tools.current?.settings) }
                    "apply-profile" -> selectedProfile?.let { Text(it.name); SettingValues(it.saved.settings, tools.current?.settings) }
                    "delete-profile" -> selectedProfile?.let { Text(it.name) }
                    "save-profile" -> { Text(profileName.trim()); tools.current?.let { SettingValues(it.settings) } }
                    "snapshot" -> tools.current?.let { SettingValues(it.settings) }
                    "ntp" -> hosts.forEachIndexed { index, host -> Text("${index + 1}. $host") }
                }
            } }, confirmButton = { TextButton(enabled = enabled, modifier = Modifier.testTag("time-preview-confirm"), onClick = {
                when (kind) {
                    "snapshot" -> actions.saveTimeSnapshot(tools.snapshot != null)
                    "restore" -> actions.restoreTimeSnapshot()
                    "save-profile" -> actions.saveTimeProfile(profileName.trim())
                    "apply-profile" -> selectedProfile?.let { actions.applyTimeProfile(it.name) }
                    "delete-profile" -> selectedProfile?.let { actions.removeTimeProfile(it.name) }
                    "ntp" -> actions.applyNtpList(hosts)
                }
                preview = null
            }) { Text(stringResource(R.string.time_preview_confirm)) } },
            dismissButton = { TextButton(onClick = { preview = null }, modifier = Modifier.testTag("time-preview-cancel")) { Text(stringResource(R.string.time_preview_cancel)) } })
    }
}

@Composable private fun TimeSourceReadStatus.localized(): String = stringResource(when (this) {
    TimeSourceReadStatus.AVAILABLE -> R.string.time_status_verified
    TimeSourceReadStatus.PERMISSION_DENIED -> R.string.time_status_permission
    TimeSourceReadStatus.UNSUPPORTED -> R.string.time_status_unsupported
    TimeSourceReadStatus.UNAVAILABLE -> R.string.time_status_unavailable
    TimeSourceReadStatus.UNPARSEABLE -> R.string.time_source_unparseable
})
