package com.civisrom.tvtimefixer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.BuildConfig
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.diagnostics.DiagnosticIssue
import com.civisrom.tvtimefixer.diagnostics.DiagnosticEvent
import com.civisrom.tvtimefixer.diagnostics.DiagnosticSnapshot
import com.civisrom.tvtimefixer.diagnostics.DiagnosticTransport
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.Outcome
import com.civisrom.tvtimefixer.diagnostics.diagnosticDetails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun Operation.labelRes(): Int = when (this) {
    Operation.APP_START -> R.string.operation_app_start
    Operation.CONNECT_NETWORK -> R.string.operation_connect_network
    Operation.CONNECT_USB -> R.string.operation_connect_usb
    Operation.PAIR -> R.string.operation_pair
    Operation.DISCONNECT -> R.string.operation_disconnect
    Operation.USB_PERMISSION -> R.string.operation_usb_permission
    Operation.USB_SCAN -> R.string.operation_usb_scan
    Operation.USB_DETACHED -> R.string.operation_usb_detached
    Operation.READ_DEVICE -> R.string.operation_read_device
    Operation.CHECK_NTP -> R.string.operation_check_ntp
    Operation.APPLY_NTP -> R.string.operation_apply_ntp
    Operation.SCAN_NTP -> R.string.operation_scan_ntp
    Operation.DISCOVERY -> R.string.operation_discovery
    Operation.CRASH -> R.string.operation_crash
    Operation.STORAGE -> R.string.operation_storage
    Operation.CHECK_TIME -> R.string.time_check_title
    Operation.APPLY_TIME_ZONE -> R.string.time_zone_apply
}

private fun DiagnosticIssue.labelRes(): Int = when (this) {
    DiagnosticIssue.NTP_UNREACHABLE -> R.string.diagnostics_ntp_unreachable
    DiagnosticIssue.NTP_UNUSABLE -> R.string.ntp_check_invalid_response
    DiagnosticIssue.NTP_NOT_CONFIRMED -> R.string.diagnostics_ntp_not_confirmed
    DiagnosticIssue.INVALID_NTP -> R.string.ntp_invalid
    DiagnosticIssue.TIME_MISMATCH -> R.string.time_check_mismatch
    DiagnosticIssue.TIME_UNCERTAIN -> R.string.time_check_uncertain
    DiagnosticIssue.TIME_UNAVAILABLE -> R.string.time_check_unavailable
    DiagnosticIssue.USB_NONE -> R.string.usb_none
    DiagnosticIssue.USB_NO_ADB -> R.string.usb_no_adb
    DiagnosticIssue.USB_ENUMERATION -> R.string.usb_scan_failed
    DiagnosticIssue.USB_HOST_UNSUPPORTED -> R.string.error_usb_unsupported
    DiagnosticIssue.INVALID_TIME_ZONE -> R.string.time_zone_invalid
    DiagnosticIssue.TIME_ZONE_UNSUPPORTED -> R.string.time_zone_unsupported
    DiagnosticIssue.TIME_ZONE_READ_FAILED -> R.string.time_zone_read_failed
    DiagnosticIssue.TIME_ZONE_AUTO_FAILED -> R.string.time_zone_auto_failed
    DiagnosticIssue.TIME_ZONE_WRITE_FAILED -> R.string.time_zone_write_failed
    DiagnosticIssue.TIME_ZONE_RESTORE_FAILED -> R.string.time_zone_restore_failed
}

private fun Outcome.labelRes(): Int = when (this) {
    Outcome.STARTED -> R.string.diagnostics_started
    Outcome.SUCCESS -> R.string.diagnostics_success
    Outcome.FAILED -> R.string.diagnostics_failed
    Outcome.CANCELLED -> R.string.diagnostics_cancelled
}

private fun eventHeading(context: Context, event: DiagnosticEvent): String {
    val result = if (event.operation == Operation.USB_SCAN && event.outcome == Outcome.SUCCESS) {
        when (event.issue) {
            DiagnosticIssue.USB_NONE -> R.string.diagnostics_usb_none
            DiagnosticIssue.USB_NO_ADB -> R.string.diagnostics_usb_no_adb
            DiagnosticIssue.USB_HOST_UNSUPPORTED -> R.string.diagnostics_usb_unsupported
            null -> R.string.diagnostics_usb_found
            else -> event.outcome.labelRes()
        }
    } else event.outcome.labelRes()
    return "${context.getString(event.operation.labelRes())} — ${context.getString(result)}"
}

private fun formatTime(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

/** Отчёт строится только из уже безопасных событий, не из состояния устройства. */
internal fun diagnosticReport(context: Context, snapshot: DiagnosticSnapshot, mode: DeviceMode): String = buildString {
    appendLine("Android TV Time Fixer ${BuildConfig.VERSION_NAME}")
    appendLine("Android API ${Build.VERSION.SDK_INT}; $mode; debug=${BuildConfig.DEBUG}")
    appendLine(context.getString(R.string.diagnostics_hint))
    if (!snapshot.storageAvailable) appendLine(context.getString(R.string.diagnostics_storage_failed))
    if (snapshot.dropped > 0) appendLine(context.getString(R.string.diagnostics_dropped, snapshot.dropped))
    snapshot.events.forEach { event ->
        appendLine("${formatTime(event.time)}  ${eventHeading(context, event)}")
        appendLine(diagnosticDetails(event))
    }
}

@Composable
internal fun DiagnosticsScreen(
    mode: DeviceMode,
    snapshot: DiagnosticSnapshot,
    selectedId: Long?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val backFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        backFocus.requestFocus()
        keyboard?.hide()
    }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var copyResult by remember { mutableStateOf<Int?>(null) }
    var expandedId by rememberSaveable(selectedId) { mutableStateOf(selectedId) }
    var located by remember(selectedId) { mutableStateOf(false) }
    val context = LocalContext.current
    val visible = snapshot.events.asReversed().filter { !errorsOnly || it.outcome == Outcome.FAILED }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, visible.map { it.id }) {
        if (!located && selectedId != null) {
            val index = visible.indexOfFirst { it.id == selectedId }
            if (index >= 0) { listState.scrollToItem(index + 1); located = true }
        }
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(
            horizontal = if (mode == DeviceMode.TELEVISION) 48.dp else 16.dp,
            vertical = if (mode == DeviceMode.TELEVISION) 27.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = onBack, modifier = Modifier.focusRequester(backFocus)
            .focusProperties { canFocus = true }.testTag("diagnostics-back")) {
            Text(stringResource(R.string.diagnostics_back))
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).testTag("diagnostics-events")) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.diagnostics_hint), style = MaterialTheme.typography.bodySmall)
                    if (!snapshot.storageAvailable) {
                        Text(stringResource(R.string.diagnostics_storage_failed), color = MaterialTheme.colorScheme.error)
                    }
                    if (snapshot.dropped > 0) Text(stringResource(R.string.diagnostics_dropped, snapshot.dropped))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !errorsOnly, onClick = { errorsOnly = false },
                            label = { Text(stringResource(R.string.diagnostics_all)) })
                        FilterChip(selected = errorsOnly, onClick = { errorsOnly = true }, modifier = Modifier.testTag("diagnostics-errors"),
                            label = { Text(stringResource(R.string.diagnostics_errors)) })
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = {
                            copyResult = if (runCatching {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Android TV Time Fixer", diagnosticReport(context, snapshot, mode)))
                            }.isSuccess) R.string.diagnostics_copied else R.string.diagnostics_copy_failed
                        }) { Text(stringResource(R.string.diagnostics_copy)) }
                        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { confirmClear = true }, enabled = snapshot.events.isNotEmpty(),
                            modifier = Modifier.testTag("diagnostics-clear")) { Text(stringResource(R.string.diagnostics_clear)) }
                    }
                    copyResult?.let { Text(stringResource(it)) }
                    if (selectedId != null && snapshot.events.none { it.id == selectedId }) {
                        Text(stringResource(R.string.diagnostics_entry_expired))
                    }
                    if (visible.isEmpty()) Text(stringResource(if (errorsOnly) R.string.diagnostics_no_errors else R.string.diagnostics_empty))
                }
            }
            items(visible, key = { it.id }) { event ->
                Card(Modifier.fillMaxWidth().testTag("diagnostic-${event.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formatTime(event.time), style = MaterialTheme.typography.bodySmall)
                        Text(eventHeading(context, event),
                            color = if (event.outcome == Outcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (event.transport != DiagnosticTransport.NONE) Text(stringResource(
                            if (event.transport == DiagnosticTransport.USB) R.string.diagnostics_transport_usb else R.string.diagnostics_transport_network))
                        event.reason?.let { Text(stringResource(it.messageRes())) }
                        event.issue?.let { Text(stringResource(it.labelRes())) }
                        TextButton(shape = MaterialTheme.shapes.medium, onClick = { expandedId = if (expandedId == event.id) null else event.id }) {
                            Text(stringResource(R.string.diagnostics_details))
                        }
                        if (expandedId == event.id) {
                            if (event.durationMs > 0) Text(stringResource(R.string.diagnostics_duration, event.durationMs))
                            SelectionContainer {
                                Text(diagnosticDetails(event), modifier = Modifier.testTag("diagnostic-details-${event.id}"),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.diagnostics_clear)) },
        text = { Text(stringResource(R.string.diagnostics_clear_confirm)) },
        confirmButton = {
            Button(shape = MaterialTheme.shapes.medium, onClick = { onClear(); confirmClear = false; expandedId = null; copyResult = null },
                modifier = Modifier.testTag("diagnostics-confirm-clear")) { Text(stringResource(R.string.diagnostics_clear)) }
        },
        dismissButton = {
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmClear = false }) { Text(stringResource(R.string.diagnostics_cancel)) }
        },
    )
}
