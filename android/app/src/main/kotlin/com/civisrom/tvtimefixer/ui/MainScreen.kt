package com.civisrom.tvtimefixer.ui

import android.os.Build
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import kotlin.math.abs
import kotlinx.coroutines.delay
import com.civisrom.tvtimefixer.BuildConfig
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.diagnostics.DiagnosticSnapshot
import com.civisrom.tvtimefixer.data.NtpCountry
import com.civisrom.tvtimefixer.data.NtpData
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.data.ScanProgress
import com.civisrom.tvtimefixer.data.searchNtpServers
import com.civisrom.tvtimefixer.data.isUsable
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.device.TimeZoneRestoration
import com.civisrom.tvtimefixer.device.availableTimeZoneIds
import com.civisrom.tvtimefixer.device.isValidTimeZoneId
import com.civisrom.tvtimefixer.diagnostics.Operation

internal const val PROJECT_REPOSITORY_URL = "https://github.com/civisrom/android-tv-date-time"

// Action buttons use shapes.medium: a full capsule clips multiline labels at large font scales.

/** Действия, которые экран запрашивает у владельца состояния. */
interface AppActions {
    fun connectFavorite(favorite: com.civisrom.tvtimefixer.data.FavoriteDevice)
    fun saveCurrentDevice(name: String)
    fun updateFavoriteDevice(favorite: com.civisrom.tvtimefixer.data.FavoriteDevice)
    fun removeFavoriteDevice(serial: String)
    fun saveFavoriteNtp(name: String, server: String)
    fun removeFavoriteNtp(server: String)
    fun openSetupSettings(action: String)
    fun connect(address: String)
    fun connectLoopback()
    fun disconnect()
    fun pairAndConnect(pairingAddress: String, code: String, connectAddress: String)
    fun checkNtpServer(server: String)
    fun applyNtpServer(server: String, allowUnverified: Boolean = false)
    fun resetNtpServer()
    fun undoNtpServer()
    fun verifyDeviceTime()
    fun applyTimeZone(zoneId: String)
    fun scanNtpServers()
    fun cancelNtpScan()
    fun clearNtpScanResults()
    fun refreshDeviceInfo()
    fun requestDiscoveryPermission()
    fun refreshUsbDevices()
    fun connectUsb(address: UsbDeviceAddress)
}

@Composable
fun MainScreen(
    mode: DeviceMode,
    state: AppState,
    actions: AppActions,
    diagnostics: DiagnosticSnapshot = DiagnosticSnapshot(),
    onRefreshDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
) {
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var selectedEvent by rememberSaveable { mutableStateOf<Long?>(null) }
    var returnFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var origin by rememberSaveable { mutableStateOf("diagnostics-open") }
    // Код не попадает в savedInstanceState и сохраняется только на время
    // текущего процесса, в том числе при просмотре диагностики.
    var pairingCode by remember { mutableStateOf("") }
    LaunchedEffect(state.connected) { if (state.connected) pairingCode = "" }
    val holder = rememberSaveableStateHolder()
    if (showSetup) {
        SetupScreen(state, actions, onBack = { showSetup = false; returnFocus = "setup-open" })
    } else if (showDiagnostics) {
        DiagnosticsScreen(mode, diagnostics, selectedEvent,
            onBack = {
                val available = when (origin) {
                    "crash-details" -> diagnostics.previousCrashId != null
                    "action-details" -> state.message != null && state.diagnosticEventId != null
                    "connection-details" -> state.connection is ConnectionState.Failed && state.diagnosticEventId != null
                    "ntp-details" -> state.ntpDiagnosticEventId != null
                    "time-details" -> state.connected && state.timeDiagnosticEventId != null
                    "time-zone-details" -> state.timeZoneDiagnosticEventId != null
                    else -> true
                }
                showDiagnostics = false; returnFocus = if (available) origin else "diagnostics-open"
            }, onClear = onClearDiagnostics)
    } else holder.SaveableStateProvider("main") {
        MainContent(mode, state, actions, diagnostics, pairingCode, { pairingCode = it },
            onDiagnostics = { id, key ->
                selectedEvent = id; origin = key; returnFocus = null
                onRefreshDiagnostics(); showDiagnostics = true
            }, returnFocus = returnFocus, onFocusRestored = { returnFocus = null }, onSetup = { showSetup = true })
    }
}

@Composable
private fun DiagnosticLink(
    eventId: Long?,
    key: String,
    onOpen: (Long?, String) -> Unit,
    returnFocus: String?,
    onFocusRestored: () -> Unit,
    title: Int = R.string.diagnostics_details,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(returnFocus) {
        if (returnFocus == key) { requester.requestFocus(); onFocusRestored() }
    }
    val modifier = Modifier.focusRequester(requester).focusProperties { canFocus = true }.testTag(key)
    if (key == "diagnostics-open") {
        FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { onOpen(eventId, key) }, modifier = modifier) { Text(stringResource(title)) }
    } else {
        TextButton(shape = MaterialTheme.shapes.medium, onClick = { onOpen(eventId, key) }, modifier = modifier) { Text(stringResource(title)) }
    }
}

@Composable
private fun MainContent(
    mode: DeviceMode,
    state: AppState,
    actions: AppActions,
    diagnostics: DiagnosticSnapshot,
    pairingCode: String,
    onPairingCode: (String) -> Unit,
    onDiagnostics: (Long?, String) -> Unit,
    returnFocus: String?,
    onFocusRestored: () -> Unit,
    onSetup: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var repositoryLinkFailed by remember { mutableStateOf(false) }
    val openRepository = stringResource(R.string.project_repository_open)
    var pairingAddress by rememberSaveable { mutableStateOf("") }
    var pairingExpanded by rememberSaveable { mutableStateOf(false) }
    var discoveryExpanded by rememberSaveable { mutableStateOf(state.discovered.isNotEmpty()) }
    var lastDiscoveredCount by rememberSaveable { mutableIntStateOf(state.discovered.size) }
    LaunchedEffect(state.discovered.size, state.discoveryPermissionNeeded) {
        if (state.discovered.size > lastDiscoveredCount || state.discoveryPermissionNeeded) discoveryExpanded = true
        lastDiscoveredCount = state.discovered.size
    }
    var usbExpanded by rememberSaveable { mutableStateOf(state.usbAttachedCount > 0 || state.usbDevices.isNotEmpty()) }
    var lastUsbCount by rememberSaveable { mutableIntStateOf(state.usbAttachedCount) }
    var lastAdbCount by rememberSaveable { mutableIntStateOf(state.usbDevices.size) }
    LaunchedEffect(state.usbAttachedCount, state.usbDevices.size) {
        if (state.usbAttachedCount > lastUsbCount || state.usbDevices.size > lastAdbCount) usbExpanded = true
        lastUsbCount = state.usbAttachedCount
        lastAdbCount = state.usbDevices.size
    }
    var focusPairing by rememberSaveable { mutableStateOf(false) }
    val pairingRequester = remember { FocusRequester() }
    LaunchedEffect(focusPairing) {
        if (focusPairing) { pairingRequester.requestFocus(); focusPairing = false }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (mode == DeviceMode.TELEVISION) 48.dp else 16.dp,
                vertical = if (mode == DeviceMode.TELEVISION) 27.dp else 24.dp)
            .testTag("main-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.project_source_code), style = MaterialTheme.typography.bodySmall)
        Text(PROJECT_REPOSITORY_URL,
            modifier = Modifier.testTag("project-repository").clickable(role = Role.Button, onClickLabel = openRepository) {
                repositoryLinkFailed = runCatching { uriHandler.openUri(PROJECT_REPOSITORY_URL) }.isFailure
            }, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodySmall)
        if (repositoryLinkFailed) Text(stringResource(R.string.project_repository_unavailable),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (mode == DeviceMode.TELEVISION) R.string.mode_television else R.string.mode_handheld),
            style = MaterialTheme.typography.bodyMedium)
        DiagnosticLink(null, "diagnostics-open", onDiagnostics, returnFocus, onFocusRestored, R.string.diagnostics_title)
        if (mode == DeviceMode.TELEVISION) {
            val setupFocus = remember { FocusRequester() }
            LaunchedEffect(returnFocus) {
                if (returnFocus == "setup-open") { setupFocus.requestFocus(); onFocusRestored() }
            }
            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = onSetup, modifier = Modifier.focusRequester(setupFocus)
                .focusProperties { canFocus = true }.testTag("setup-open")) {
                Text(stringResource(R.string.setup_title))
            }
        }
        diagnostics.previousCrashId?.let { id ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.diagnostics_previous_crash))
                    DiagnosticLink(id, "crash-details", onDiagnostics, returnFocus, onFocusRestored)
                }
            }
        }
        ConnectionStatus(mode, state, actions)
        FunctionCard("discovery") {
            ExpandableSection(stringResource(R.string.discovery_title), "discovery", expanded = discoveryExpanded,
                onExpanded = { discoveryExpanded = it }) {
                DiscoverySection(state, actions, onPair = {
                    pairingAddress = it; pairingExpanded = true; focusPairing = true
                })
            }
        }
        FunctionCard("favorites") {
            ExpandableSection(stringResource(R.string.favorite_devices), "favorites") { DeviceFavorites(state, actions) }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.operation?.let { Text(stringResource(R.string.operation_working, stringResource(it.labelRes()))) }
        }
        state.message?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    CopyableText(stringResource(message.res, *message.args.toTypedArray()))
                    state.diagnosticEventId?.let { id ->
                        DiagnosticLink(id, "action-details", onDiagnostics, returnFocus, onFocusRestored)
                    }
                }
            }
        }
        if (state.connection is ConnectionState.Failed && state.message == null) {
            state.diagnosticEventId?.let { id ->
                DiagnosticLink(id, "connection-details", onDiagnostics, returnFocus, onFocusRestored)
            }
        }
        NtpSection(state, actions, onDiagnostics, returnFocus, onFocusRestored)
        FunctionCard("timezone") {
            ExpandableSection(stringResource(R.string.time_zone_title), "timezone") {
                TimeZoneSection(state, actions, onDiagnostics, returnFocus, onFocusRestored)
            }
        }
        FunctionCard("pairing") {
            ExpandableSection(stringResource(R.string.pairing_title), "pairing", expanded = pairingExpanded,
                onExpanded = { pairingExpanded = it }) {
                PairingSection(state, actions, pairingAddress, { pairingAddress = it },
                    pairingCode, onPairingCode, pairingRequester)
            }
        }
        FunctionCard("usb") {
            ExpandableSection(stringResource(R.string.usb_title), "usb", expanded = usbExpanded,
                onExpanded = { usbExpanded = it }) {
                UsbSection(state, actions)
            }
        }
        if (state.connected) DeviceInfoSection(state, actions)
    }
}

@Composable
private fun FunctionCard(key: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("function-$key")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Отдельное выделение строки не захватывает соседние подписи и адреса. */
@Composable
private fun CopyableText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    SelectionContainer {
        Text(text, modifier, color = color, fontWeight = fontWeight, style = style)
    }
}

/** Скрытая форма сохраняет rememberSaveable, но не остаётся в дереве фокуса. */
@Composable
private fun ExpandableSection(
    title: String,
    key: String,
    expanded: Boolean? = null,
    onExpanded: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var localExpanded by rememberSaveable { mutableStateOf(false) }
    val open = expanded ?: localExpanded
    val description = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed)
    val holder = rememberSaveableStateHolder()
    val sectionFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(shape = MaterialTheme.shapes.medium,
            onClick = {
                sectionFocus.requestFocus()
                keyboard?.hide()
                if (onExpanded != null) onExpanded(!open) else localExpanded = !open
            },
            modifier = Modifier.focusRequester(sectionFocus).focusProperties { canFocus = true }
                .testTag("section-$key").semantics { stateDescription = description },
        ) { Text((if (open) "− " else "+ ") + title, style = MaterialTheme.typography.titleMedium) }
        if (open) holder.SaveableStateProvider(key) { content() }
    }
}

@Composable
private fun UsbSection(state: AppState, actions: AppActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.usbSupported) {
            Text(stringResource(R.string.error_usb_unsupported))
        } else {
            CopyableText(stringResource(R.string.usb_setup_hint), style = MaterialTheme.typography.bodySmall)
            Button(shape = MaterialTheme.shapes.medium, onClick = actions::refreshUsbDevices, enabled = !state.busy, modifier = Modifier.testTag("usb-refresh")) {
                Text(stringResource(R.string.usb_refresh))
            }
            when {
                state.usbScanFailed -> Text(stringResource(R.string.usb_scan_failed))
                state.usbAttachedCount == 0 && state.usbDevices.isEmpty() -> Text(stringResource(R.string.usb_none))
                state.usbDevices.isEmpty() -> Text(stringResource(R.string.usb_no_adb))
                else -> Text(stringResource(R.string.usb_detected, state.usbDevices.size))
            }
            if (state.usbDevices.isEmpty()) ExpandableSection(stringResource(R.string.usb_connection_help), "usb-help") {
                CopyableText(stringResource(R.string.usb_help_host), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_help_cable), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_help_port), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_help_debugging), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_help_retry), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_shield_hint), style = MaterialTheme.typography.bodySmall)
                CopyableText(stringResource(R.string.usb_help_desktop), style = MaterialTheme.typography.bodySmall)
            }
            state.usbDevices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CopyableText(device.label, style = MaterialTheme.typography.titleSmall)
                        // USB bus address distinguishes identical devices without reading serialNumber.
                        CopyableText(device.deviceName, style = MaterialTheme.typography.bodySmall)
                        if (state.connectedUsb?.deviceName == device.deviceName) {
                            Text(stringResource(R.string.usb_connected), color = ConnectedColor)
                        } else {
                            Button(shape = MaterialTheme.shapes.medium, onClick = { actions.connectUsb(device) }, enabled = !state.busy) {
                                Text(stringResource(R.string.usb_connect))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatus(mode: DeviceMode, state: AppState, actions: AppActions) {
    Card(Modifier.fillMaxWidth().testTag("connection-status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
            CopyableText(when (val connection = state.connection) {
                is ConnectionState.Connected -> stringResource(R.string.connect_state_connected, connection.address.toString())
                is ConnectionState.Connecting -> stringResource(R.string.connect_state_connecting, connection.address.toString())
                is ConnectionState.Checking -> stringResource(R.string.connect_state_checking, connection.address.toString())
                is ConnectionState.Failed -> stringResource(connection.reason.messageRes())
                ConnectionState.Disconnected -> stringResource(R.string.connect_state_disconnected)
            }, fontWeight = FontWeight.Bold, color = when (state.connection) {
                is ConnectionState.Connected -> ConnectedColor
                is ConnectionState.Connecting, is ConnectionState.Checking -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.error
            })
            if (state.connected) Button(shape = MaterialTheme.shapes.medium, onClick = actions::disconnect, enabled = !state.busy) {
                Text(stringResource(R.string.connect_disconnect))
            }
            NetworkAddressSection(mode, state, actions)
        }
    }
}

@Composable
private fun NetworkAddressSection(mode: DeviceMode, state: AppState, actions: AppActions) {
    var address by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value = address, onValueChange = { address = it },
        label = { Text(stringResource(R.string.connect_address_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().testTag("network-address"))
    AddressPaste("network-address") { address = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(shape = MaterialTheme.shapes.medium, onClick = { actions.connect(address) }, enabled = !state.busy, modifier = Modifier.testTag("network-connect")) {
            Text(stringResource(R.string.connect_action))
        }
        if (mode == DeviceMode.TELEVISION) TextButton(shape = MaterialTheme.shapes.medium, onClick = actions::connectLoopback, enabled = !state.busy) {
            Text(stringResource(R.string.connect_try_loopback))
        }
    }
    CopyableText(stringResource(R.string.discovery_authorize_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DiscoverySection(state: AppState, actions: AppActions, onPair: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // Без разрешения системный mDNS не вернёт ничего и не пожалуется:
            // отличить это от «в сети пусто» человек сам не сможет
            state.discoveryPermissionNeeded -> {
                Text(stringResource(R.string.discovery_permission_needed))
                Button(shape = MaterialTheme.shapes.medium, onClick = actions::requestDiscoveryPermission, enabled = !state.busy) {
                    Text(stringResource(R.string.discovery_grant_permission))
                }
            }
            !state.discoveryAvailable -> Text(stringResource(R.string.discovery_unavailable))
            state.discoverySearching && state.discovered.isEmpty() ->
                Text(stringResource(R.string.discovery_searching))
            state.discovered.isEmpty() -> Text(stringResource(R.string.discovery_empty))
        }
        state.discovered.forEach { device ->
            DiscoveredRow(
                device = device,
                enabled = !state.busy,
                // Строка того устройства, с которым связь уже установлена,
                // не должна предлагать подключиться: вверху экрана в это же
                // время написано «Подключено», и человек не понимает, чему
                // верить. Сравнение именно с connectedAddress: addressOrNull()
                // отдаёт адрес и при отказе, и тогда кнопка пропадала бы
                // ровно там, где нужна вторая попытка
                connected = device.address == state.connectedAddress,
                onConnect = actions::connect,
                onPair = onPair,
            )
        }
        if (state.discovered.isEmpty()) {
            CopyableText(stringResource(R.string.discovery_authorize_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiscoveredRow(
    device: DiscoveredDevice,
    enabled: Boolean,
    connected: Boolean,
    onConnect: (String) -> Unit,
    onPair: (String) -> Unit,
) {
    val awaitingPairing = device.kind == DiscoveredDevice.Kind.AWAITING_PAIRING
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.discovery_found), color = ConnectedColor, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge)
            CopyableText(device.name, style = MaterialTheme.typography.bodyLarge)
            FlowRow {
                CopyableText(device.address.toString())
                Text("  ·  ${stringResource(device.kind.labelRes())}")
            }
            AddressCopy(device.address.toString(), "discovered-${device.address}")
            if (connected) {
                Text(
                    stringResource(R.string.discovery_connected),
                    color = ConnectedColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (awaitingPairing) {
                // Подключаться к такому устройству нечем: сперва код. Кнопка
                // переносит адрес в форму спаривания — раньше это приходилось
                // делать вручную, переписывая порт с экрана телевизора
                Button(shape = MaterialTheme.shapes.medium, onClick = { onPair(device.address.toString()) }, enabled = enabled) {
                    Text(stringResource(R.string.discovery_pair_action))
                }
            } else {
                Button(shape = MaterialTheme.shapes.medium, onClick = { onConnect(device.address.toString()) }, enabled = enabled) {
                    Text(stringResource(R.string.connect_action))
                }
            }
            CopyableText(stringResource(R.string.discovery_authorize_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PairingSection(
    state: AppState,
    actions: AppActions,
    pairingAddress: String,
    onPairingAddressChange: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    codeFocus: FocusRequester,
) {
    var connectAddress by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val submitFocus = remember { FocusRequester() }
    val pairingSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CopyableText(stringResource(R.string.pairing_hint), style = MaterialTheme.typography.bodySmall)
        if (!pairingSupported) {
            Text(stringResource(R.string.error_wireless_unsupported), style = MaterialTheme.typography.bodySmall)
        }
        // Самая частая причина неудачи: люди подставляют порт спаривания
        // в подключение, потому что оба показаны на одном экране телевизора
        CopyableText(stringResource(R.string.pairing_port_warning), style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = pairingAddress,
            onValueChange = onPairingAddressChange,
            label = { Text(stringResource(R.string.pairing_address_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pairing-address"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        AddressPaste("pairing-address", onPairingAddressChange)
        OutlinedTextField(
            value = code,
            onValueChange = onCode,
            label = { Text(stringResource(R.string.pairing_code_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).testTag("pairing-code"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        )
        OutlinedTextField(
            value = connectAddress,
            onValueChange = { connectAddress = it },
            label = { Text(stringResource(R.string.pairing_connect_address_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pairing-connect-address"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (pairingSupported && !state.busy) submitFocus.requestFocus()
                keyboard?.hide()
            }),
        )
        AddressPaste("pairing-connect-address") { connectAddress = it }
        Button(shape = MaterialTheme.shapes.medium,
            onClick = { actions.pairAndConnect(pairingAddress, code, connectAddress) },
            enabled = !state.busy && pairingSupported,
            modifier = Modifier.focusRequester(submitFocus).focusProperties { canFocus = true }.testTag("pairing-connect"),
        ) {
            Text(stringResource(R.string.pairing_action))
        }
    }
}

@Composable
private fun NtpSection(state: AppState, actions: AppActions,
    onDiagnostics: (Long?, String) -> Unit, returnFocus: String?, onFocusRestored: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var confirmedServer by remember { mutableStateOf("") }
    LaunchedEffect(state.connection) { confirmation = null }
    if (confirmation != null) AlertDialog(
        onDismissRequest = { confirmation = null },
        title = { Text(stringResource(R.string.ntp_confirm_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (confirmation == "unverified") R.string.ntp_unverified_note else R.string.ntp_restore_note))
            Text(stringResource(R.string.ntp_current, state.currentNtpServer.ifEmpty { "—" }))
            Text(stringResource(R.string.ntp_new_value, if (confirmedServer == "null")
                stringResource(R.string.ntp_system_default) else confirmedServer))
        } },
        confirmButton = { Button(shape = MaterialTheme.shapes.medium, onClick = {
            if (state.connected && !state.busy) when (confirmation) {
                "reset" -> actions.resetNtpServer()
                "undo" -> actions.undoNtpServer()
                "unverified" -> actions.applyNtpServer(confirmedServer, allowUnverified = true)
            }
            confirmation = null
        }, enabled = state.connected && !state.busy, modifier = Modifier.testTag("ntp-confirm")) {
            Text(stringResource(R.string.ntp_confirm_action))
        } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmation = null }, modifier = Modifier.testTag("ntp-confirm-cancel")) {
            Text(stringResource(R.string.diagnostics_cancel))
        } },
    )
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showCountries by rememberSaveable { mutableStateOf(false) }
    var pickerExpanded by rememberSaveable { mutableStateOf(false) }
    val addressView = remember { BringIntoViewRequester() }
    val checkFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var selectionRequest by remember { mutableIntStateOf(0) }
    var highlightAddress by remember { mutableStateOf(false) }
    val onPick: (String) -> Unit = { server ->
        custom = server
        showCountries = false
        showAll = false
        selectionRequest += 1
    }
    LaunchedEffect(selectionRequest) {
        highlightAddress = false
        if (selectionRequest > 0) {
            withFrameNanos { }
            // clearFocus() на API 23 может вернуть фокус в поиск и открыть IME
            // снова. Переводим его на доступную без подключения кнопку.
            checkFocus.requestFocus()
            keyboard?.hide()
            addressView.bringIntoView()
            // Небольшая подсказка после выбора, без изменения размеров и действий.
            repeat(3) {
                highlightAddress = true
                delay(400)
                highlightAddress = false
                delay(400)
            }
        }
    }

    // Поиск идёт и по странам, и по альтернативным адресам: для человека это
    // один список серверов, а не две разные сущности
    // Сам поиск — чистая функция в data/NtpSearch.kt, чтобы он проверялся
    // тестами: промах здесь выглядит как «поиск не работает», и отличить его
    // от опечатки пользователя без теста невозможно
    val matches = remember(query) { searchNtpServers(query) }

    FunctionCard("ntp") {
        Text(stringResource(R.string.ntp_title), style = MaterialTheme.typography.titleMedium)
        // Заданный сервер выделен так же, как установленная связь: это второе
        // состояние, ради которого на экран смотрят
        val ntpIsSet = state.connected && state.currentNtpServer.isNotEmpty()
        CopyableText(
            when {
                !state.connected -> stringResource(R.string.ntp_connect_first)
                state.currentNtpServer == "null" -> stringResource(R.string.ntp_system_default)
                ntpIsSet -> stringResource(R.string.ntp_current, state.currentNtpServer)
                else -> stringResource(R.string.ntp_current_unset)
            },
            color = if (ntpIsSet) ConnectedColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (ntpIsSet) FontWeight.Bold else null,
            style = MaterialTheme.typography.titleSmall,
        )

        Column(Modifier.bringIntoViewRequester(addressView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text(stringResource(R.string.ntp_custom_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (highlightAddress) ConnectedColor else MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = if (highlightAddress) ConnectedColor else MaterialTheme.colorScheme.outline,
                    focusedContainerColor = if (highlightAddress) ConnectedColor.copy(alpha = 0.12f) else Color.Transparent,
                    unfocusedContainerColor = if (highlightAddress) ConnectedColor.copy(alpha = 0.12f) else Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().testTag("ntp-address"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!state.busy && custom.isNotBlank()) checkFocus.requestFocus()
                    keyboard?.hide()
                }),
            )
            AddressPaste("ntp-address") { custom = it }
            NtpFavorites(state, actions, custom, onPick)
            Text(
                stringResource(R.string.ntp_address_note),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = MaterialTheme.shapes.medium,
                    onClick = { actions.applyNtpServer(custom) },
                    enabled = state.connected && !state.busy && custom.isNotBlank(),
                    modifier = Modifier.testTag("ntp-apply"),
                ) {
                    Text(stringResource(R.string.ntp_apply))
                }
                FilledTonalButton(shape = MaterialTheme.shapes.medium,
                    onClick = { actions.checkNtpServer(custom) },
                    enabled = !state.busy && custom.isNotBlank(),
                    modifier = Modifier.focusRequester(checkFocus)
                        .focusProperties { canFocus = true }.testTag("ntp-check"),
                ) {
                    Text(stringResource(R.string.ntp_check))
                }
            }
        }

        state.ntpCheck?.let { NtpCheckCard(it) }
        if (state.connected && !state.busy && state.ntpCheck?.server == custom.trim() &&
            state.ntpCheck?.isUsable() == false && com.civisrom.tvtimefixer.data.isValidNtpServer(custom)) {
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmedServer = custom.trim(); confirmation = "unverified" },
                modifier = Modifier.testTag("ntp-unverified")) { Text(stringResource(R.string.ntp_save_unverified)) }
        }
        if (state.connected) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmedServer = "null"; confirmation = "reset" },
                enabled = !state.busy, modifier = Modifier.testTag("ntp-reset")) {
                Text(stringResource(R.string.ntp_reset))
            }
            state.ntpChange?.let { change ->
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { confirmedServer = change.previous; confirmation = "undo" },
                    enabled = !state.busy && change.server == state.currentNtpServer,
                    modifier = Modifier.testTag("ntp-undo")) { Text(stringResource(R.string.ntp_undo)) }
            }
        }

        // Итог показывается здесь, а не в карточке вверху экрана: раздел
        // находится далеко внизу, и подтверждение там не видно
        state.ntpMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                CopyableText(
                    text = stringResource(message.res, *message.args.toTypedArray()),
                    // Успешная запись — зелёным, всё остальное здесь неудача:
                    // «не применено», «неверный адрес», «устройство сообщает
                    // другое значение»
                    color = if (message.res in listOf(R.string.ntp_applied, R.string.ntp_default_applied)) {
                        ConnectedColor
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        state.ntpChange?.takeIf { state.connected }?.let { NtpChangeCard(state, it) }

        state.ntpDiagnosticEventId?.let { id ->
            DiagnosticLink(id, "ntp-details", onDiagnostics, returnFocus, onFocusRestored)
        }
        if (state.connected) {
            Button(shape = MaterialTheme.shapes.medium, onClick = actions::verifyDeviceTime, enabled = !state.busy,
                modifier = Modifier.testTag("time-check")) {
                Text(stringResource(R.string.time_check_action))
            }
            if (state.operation == Operation.CHECK_TIME) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.time_check_working), modifier = Modifier.testTag("time-check-working"))
            }
            state.timeCheck?.let { DeviceTimeCard(it) }
            state.timeDiagnosticEventId?.let { id ->
                DiagnosticLink(id, "time-details", onDiagnostics, returnFocus, onFocusRestored)
            }
        }
        if (!pickerExpanded) state.ntpScan?.takeUnless { it.finished }?.let { scan ->
            NtpScanProgress(scan, actions)
        }
        ExpandableSection(stringResource(R.string.ntp_choose_server), "ntp-picker", expanded = pickerExpanded,
            onExpanded = { pickerExpanded = it }) {
            Text(stringResource(R.string.ntp_by_country), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.ntp_search_country), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("ntp-search"),
            )
            matches.forEach { match ->
                val country = match.country
                val label = if (country == null) {
                    match.server
                } else {
                    "${country.code.uppercase()} · ${countryName(country)} · ${country.server}"
                }
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { onPick(match.server) }, enabled = !state.busy) { CopyableText(label) }
            }

            // Списки раскрываются только при пустом поиске: иначе на экране
            // оказались бы сразу и результаты поиска, и весь справочник
            if (query.isBlank()) {
                Button(shape = MaterialTheme.shapes.medium, onClick = { showCountries = !showCountries }, modifier = Modifier.testTag("ntp-countries")) {
                    Text(
                        if (showCountries) {
                            stringResource(R.string.ntp_hide_countries)
                        } else {
                            stringResource(R.string.ntp_show_countries, NtpData.countries.size)
                        },
                    )
                }
                if (showCountries) {
                    // Выбор закрывает список: адрес уже в поле ввода, а открытый
                    // справочник закрывает собой кнопки «Применить» и «Проверить»
                    NtpData.countries.forEach { country ->
                        TextButton(shape = MaterialTheme.shapes.medium,
                            onClick = { onPick(country.server) },
                            enabled = !state.busy,
                        ) {
                            CopyableText("${country.code.uppercase()} · ${countryName(country)} · ${country.server}")
                        }
                    }
                }

                Button(shape = MaterialTheme.shapes.medium, onClick = { showAll = !showAll }, modifier = Modifier.testTag("ntp-alternatives")) {
                    Text(
                        if (showAll) {
                            stringResource(R.string.ntp_hide_all)
                        } else {
                            stringResource(R.string.ntp_show_all, NtpData.alternativeServers.size)
                        },
                    )
                }
                if (showAll) {
                    NtpData.alternativeServers.forEach { server ->
                        TextButton(shape = MaterialTheme.shapes.medium,
                            onClick = { onPick(server) },
                            enabled = !state.busy,
                        ) { CopyableText(server) }
                        AddressCopy(server, "ntp-$server")
                    }
                }
            }

            NtpScanBlock(state, actions, onPick = { address ->
                actions.clearNtpScanResults()
                onPick(address)
            }, onStart = {
                showCountries = false
                showAll = false
                actions.scanNtpServers()
            })
        }
    }
}

@Composable
private fun NtpChangeCard(state: AppState, change: com.civisrom.tvtimefixer.device.NtpUpdateResult.Applied) {
    fun value(raw: String) = if (raw == "null") "" else raw
    val system = stringResource(R.string.ntp_system_default)
    fun label(raw: String) = value(raw).ifEmpty { system }
    Card(Modifier.fillMaxWidth().testTag("ntp-change-result")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ntp_before_after, label(change.previous), label(change.server)), fontWeight = FontWeight.Bold)
            fun measurement(check: DeviceTimeCheck?): String = check?.differenceSeconds?.let { delta ->
                check.uncertaintySeconds?.let { String.format(Locale.ROOT, "%.2f ± %.2f s", delta, it) }
            } ?: "—"
            val before = measurement(state.ntpBeforeTime)
            val after = measurement(state.timeCheck)
            Text(stringResource(R.string.ntp_clock_before_after, before, after))
            Text(stringResource(when (change.activation) {
                com.civisrom.tvtimefixer.device.NtpActivation.RESTART_REQUIRED -> R.string.ntp_restart_required
                com.civisrom.tvtimefixer.device.NtpActivation.NEXT_REFRESH -> R.string.ntp_next_refresh
                com.civisrom.tvtimefixer.device.NtpActivation.UNKNOWN -> R.string.ntp_activation_unknown
            }))
            Text(stringResource(when (change.automaticTime) {
                true -> R.string.ntp_auto_on
                false -> R.string.time_check_auto_off
                null -> R.string.time_check_auto_unknown
            }))
            Text(stringResource(R.string.time_check_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Ручной выбор пояса подключённого устройства, отдельно от настройки NTP. */
@Composable
private fun TimeZoneSection(state: AppState, actions: AppActions,
    onDiagnostics: (Long?, String) -> Unit, returnFocus: String?, onFocusRestored: () -> Unit,
) {
    val unsupported = state.deviceInfo?.apiLevel?.toIntOrNull()?.let { it < 28 } == true
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf("") }
    var showChoices by rememberSaveable { mutableStateOf(false) }
    var selectionRequest by remember { mutableIntStateOf(0) }
    val applyFocus = remember { FocusRequester() }
    val applyView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val catalog = remember(locale) {
        val names = if (Build.VERSION.SDK_INT >= 24) android.icu.text.TimeZoneNames.getInstance(locale) else null
        availableTimeZoneIds.filter { '/' in it || it == "UTC" }.sorted().map { id ->
            val city = if (Build.VERSION.SDK_INT >= 24) names?.getExemplarLocationName(id) else null
            val description = TimeZone.getTimeZone(id).getDisplayName(false, TimeZone.LONG, locale)
            id to listOfNotNull(city, description, id).distinct().joinToString(" · ")
        }
    }
    val matches = remember(query, catalog) {
        if (query.isBlank()) emptyList() else catalog.filter { (_, label) -> label.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(selectionRequest, state.connected) {
        if (selectionRequest > 0 && state.connected) {
            withFrameNanos { }
            applyFocus.requestFocus()
            keyboard?.hide()
            applyView.bringIntoView()
        }
    }
    CopyableText(stringResource(R.string.time_zone_note), style = MaterialTheme.typography.bodySmall)
    if (unsupported) Text(stringResource(R.string.time_zone_unsupported))
    if (state.connected) {
        val current = (state.timeZoneResult as? TimeZoneUpdateResult.Applied)?.zoneId
            ?: state.deviceInfo?.timezone.orEmpty()
        CopyableText(if (current.isNotBlank()) stringResource(R.string.time_zone_current, current)
            else stringResource(R.string.time_zone_unknown),
            color = if (current.isNotBlank()) ConnectedColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("time-zone-current"))
        OutlinedTextField(query, onValueChange = {
            query = it
            chosen = it.trim().takeIf(::isValidTimeZoneId).orEmpty()
            showChoices = true
        }, singleLine = true, enabled = !state.busy,
            label = { Text(stringResource(R.string.time_zone_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.fillMaxWidth().testTag("time-zone-search"))
        Text(stringResource(R.string.time_zone_search_hint), style = MaterialTheme.typography.bodySmall)
        Button(shape = MaterialTheme.shapes.medium, onClick = {
            showChoices = false
            applyFocus.requestFocus()
            keyboard?.hide()
            actions.applyTimeZone(chosen)
        }, enabled = !state.busy && !unsupported && isValidTimeZoneId(chosen),
            modifier = Modifier.focusRequester(applyFocus).focusProperties { canFocus = true }
                .bringIntoViewRequester(applyView).testTag("time-zone-apply")) {
            Text(stringResource(R.string.time_zone_apply))
        }
        if (showChoices && query.isNotBlank()) {
            matches.take(20).forEach { (id, label) ->
                TextButton(shape = MaterialTheme.shapes.medium, onClick = {
                    query = id; chosen = id; showChoices = false; selectionRequest++
                }, enabled = !state.busy, modifier = Modifier.testTag("time-zone-option-$id")) { CopyableText(label) }
            }
            if (matches.isEmpty()) Text(stringResource(R.string.time_zone_no_matches))
            if (matches.size > 20) Text(stringResource(R.string.time_zone_refine))
        }
    } else Text(stringResource(R.string.time_zone_connect_first))
    if (state.operation == Operation.APPLY_TIME_ZONE) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.time_zone_working))
    }
    when (val result = state.timeZoneResult) {
        is TimeZoneUpdateResult.Applied -> CopyableText(stringResource(R.string.time_zone_applied, result.zoneId), color = ConnectedColor)
        is TimeZoneUpdateResult.Failed -> {
            CopyableText(stringResource(result.reason.messageRes()), color = MaterialTheme.colorScheme.error)
            CopyableText(stringResource(when (result.restoration) {
                TimeZoneRestoration.NOT_NEEDED -> R.string.time_zone_unchanged
                TimeZoneRestoration.RESTORED -> R.string.time_zone_restored
                TimeZoneRestoration.UNCONFIRMED -> R.string.time_zone_restore_failed
            }), color = MaterialTheme.colorScheme.error)
        }
        null -> Unit
    }
    state.timeZoneDiagnosticEventId?.let { id ->
        DiagnosticLink(id, "time-zone-details", onDiagnostics, returnFocus, onFocusRestored)
    }
}

/** Вердикт по одному адресу: отвечает ли он как сервер времени. */
@Composable
private fun NtpCheckCard(check: NtpProbeResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CopyableText(check.server, style = MaterialTheme.typography.bodyLarge)
            // Здесь строка не нажимается, поэтому и текст другой: обещать
            // нажатие там, где его нет, хуже, чем не показывать вовсе
            check.ipAddress?.takeIf { it != check.server }?.let {
                CopyableText(stringResource(R.string.ntp_check_ip, it), style = MaterialTheme.typography.bodySmall)
            }
            if (check.isUsable()) {
                Text(
                    stringResource(
                        R.string.ntp_check_ok,
                        check.avgRttMs ?: 0L,
                        check.successRate,
                        formatOffset(check.offsetSeconds),
                    ),
                    color = ConnectedColor,
                )
            } else {
                Text(
                    stringResource(R.string.ntp_check_failed, stringResource(check.rejectionMessageRes())),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DeviceTimeCard(check: DeviceTimeCheck) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    Card(Modifier.fillMaxWidth().testTag("time-check-result")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.time_check_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(check.status.messageRes()), modifier = Modifier.testTag("time-check-status"),
                color = when (check.status) {
                    DeviceTimeStatus.MATCH -> ConnectedColor
                    DeviceTimeStatus.MISMATCH -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                })
            if (check.server.isNotEmpty()) CopyableText(stringResource(R.string.time_check_server, check.server))
            check.deviceTimeMillis?.let {
                check.timeZoneId?.let { zone ->
                    val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).apply {
                        timeZone = TimeZone.getTimeZone(zone)
                    }.format(Date(it))
                    CopyableText(stringResource(R.string.time_check_device_local_time, local, zone))
                }
                val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(it))
                Text(stringResource(R.string.time_check_device_time, utc))
                Text(stringResource(R.string.time_check_utc_note), style = MaterialTheme.typography.bodySmall)
            }
            if (check.differenceSeconds != null && check.uncertaintySeconds != null) {
                Text(stringResource(R.string.time_check_difference, formatOffset(check.differenceSeconds, locale),
                    String.format(locale, "%.1f", check.uncertaintySeconds)))
            }
            Text(stringResource(when (check.automaticTime) {
                true -> R.string.time_check_auto_on
                false -> R.string.time_check_auto_off
                null -> R.string.time_check_auto_unknown
            }), color = if (check.automaticTime == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.time_check_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Подбор устойчиво отвечающего сервера — аналог автонастройки десктопной версии. */
@Composable
private fun NtpScanBlock(state: AppState, actions: AppActions, onPick: (String) -> Unit, onStart: () -> Unit) {
    state.ntpScan?.takeIf { it.cancelled }?.let {
        Text(stringResource(R.string.ntp_scan_stopped, it.checked, it.total))
    }
    val scan = state.ntpScan
    val progressView = remember { BringIntoViewRequester() }
    val scanning = scan != null && !scan.finished
    LaunchedEffect(scanning) {
        if (scanning) {
            withFrameNanos { }
            progressView.bringIntoView()
        }
    }
    Text(stringResource(R.string.ntp_scan_title), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.ntp_scan_hint), style = MaterialTheme.typography.bodySmall)

    if (scan == null || scan.finished) {
        Button(shape = MaterialTheme.shapes.medium, onClick = onStart, enabled = !state.busy, modifier = Modifier.testTag("ntp-scan-start")) {
            Text(stringResource(R.string.ntp_scan_start))
        }
    } else {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(progressView),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NtpScanProgress(scan, actions)
        }
    }

    if (scan != null && scan.best.isNotEmpty()) {
        Text(stringResource(R.string.ntp_scan_best), style = MaterialTheme.typography.bodyMedium)
        scan.best.forEach { result ->
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { onPick(result.server) }, enabled = !state.busy) {
                CopyableText(
                    stringResource(
                        R.string.ntp_scan_entry,
                        result.server,
                        result.medianRttMs ?: result.avgRttMs ?: 0L,
                        result.successRate,
                        result.rttJitterMs?.toLong() ?: 0L,
                    ),
                )
            }
            // IP показывается отдельной нажимаемой строкой: часть прошивок
            // не умеет резолвить имена, и тогда адрес нужно задавать числом.
            // Запрос DNS ради этого не делается — адрес уже известен от пробы.
            val ip = result.ipAddress
            if (ip != null && ip != result.server) {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { onPick(ip) }, enabled = !state.busy) {
                    CopyableText(stringResource(R.string.ntp_scan_entry_ip, ip))
                }
            }
        }
    } else if (scan != null && scan.finished) {
        Text(stringResource(R.string.ntp_scan_none))
    }
}

@Composable
private fun NtpScanProgress(scan: ScanProgress, actions: AppActions) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(R.string.ntp_scan_progress, scan.checked, scan.total, scan.best.size),
        modifier = Modifier.testTag("ntp-scan-progress"))
    Button(shape = MaterialTheme.shapes.medium, onClick = actions::cancelNtpScan, modifier = Modifier.testTag("ntp-scan-cancel")) {
        Text(stringResource(R.string.ntp_scan_cancel))
    }
}

/**
 * Зелёный для установленной связи.
 *
 * Задан явно, а не взят из схемы: в Material 3 нет роли «успех», а
 * `primary` на светлой теме фиолетовый и о состоянии ничего не говорит.
 * Отказ при этом берёт `error` из темы — там подходящая роль есть.
 */
private val ConnectedColor = Color(0xFF166534)

/**
 * Название страны на языке интерфейса.
 *
 * Справочник хранит оба названия, и показывать русское англоязычному
 * пользователю нельзя — искать он будет по английскому.
 */
private fun countryName(country: NtpCountry): String =
    if (Locale.getDefault().language == "ru") country.nameRu else country.nameEn

/**
 * Смещение часов с явным знаком: «+0,4» читается лучше, чем «0.4».
 *
 * Локаль берётся пользовательская намеренно: это число человек читает глазами,
 * и в русском разделителем должна быть запятая.
 */
private fun formatOffset(seconds: Double?, locale: Locale = Locale.getDefault()): String {
    val value = seconds ?: return "—"
    val sign = if (value >= 0) "+" else "-"
    return sign + String.format(locale, "%.1f", abs(value))
}

@Composable
private fun DeviceInfoSection(state: AppState, actions: AppActions) {
    FunctionCard("device-info") {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleMedium)
        state.deviceInfo?.let { info ->
            InfoRow(stringResource(R.string.info_model), info.model)
            InfoRow(stringResource(R.string.info_android), info.androidVersion)
            InfoRow(stringResource(R.string.info_ntp), info.currentNtpServer, color = ConnectedColor)
            InfoRow(stringResource(R.string.info_timezone), info.timezone)
            ExpandableSection(stringResource(R.string.info_more), "device-details") {
                Text(stringResource(R.string.info_availability_note), style = MaterialTheme.typography.bodySmall)
                InfoGroup(stringResource(R.string.info_group_system), listOf(
                    stringResource(R.string.info_manufacturer) to info.manufacturer,
                    stringResource(R.string.info_device_code) to info.deviceCode,
                    stringResource(R.string.info_api) to info.apiLevel,
                    stringResource(R.string.info_build) to info.buildDisplay,
                    stringResource(R.string.info_patch) to info.securityPatch,
                    stringResource(R.string.info_vendor_patch) to info.vendorSecurityPatch,
                    stringResource(R.string.info_build_type) to info.buildType,
                    stringResource(R.string.info_fingerprint) to info.buildFingerprint,
                    stringResource(R.string.info_bootloader) to info.bootloader,
                    stringResource(R.string.info_kernel) to info.kernelVersion,
                    stringResource(R.string.info_serial) to info.serial,
                ))
                InfoGroup(stringResource(R.string.info_group_hardware), listOf(
                    stringResource(R.string.info_soc) to info.socModel,
                    stringResource(R.string.info_soc_manufacturer) to info.socManufacturer,
                    stringResource(R.string.info_hardware) to info.hardware,
                    stringResource(R.string.info_cpu) to info.cpuAbi,
                    stringResource(R.string.info_cores) to info.cpuCores,
                    stringResource(R.string.info_gpu) to info.gpu,
                    stringResource(R.string.info_ram) to info.totalRam,
                    stringResource(R.string.info_ram_free) to info.availableRam,
                    stringResource(R.string.info_storage) to info.storageTotal,
                    stringResource(R.string.info_storage_free) to info.storageAvailable,
                    stringResource(R.string.info_battery) to info.batteryLevel,
                ))
                val hdr = info.display.hdrTypes?.let { types ->
                    if (types.isEmpty()) stringResource(R.string.info_hdr_none) else types.joinToString(", ") {
                        when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "HDR #$it" }
                    }
                }.orEmpty()
                InfoGroup(stringResource(R.string.info_group_display), listOf(
                    stringResource(R.string.info_screen) to info.screenResolution,
                    stringResource(R.string.info_density) to info.screenDensity,
                    stringResource(R.string.info_display_mode) to info.display.activeMode,
                    stringResource(R.string.info_display_modes) to info.display.supportedModes,
                    stringResource(R.string.info_hdr) to hdr,
                    stringResource(R.string.info_allm) to infoBoolean(info.display.allm),
                ))
                InfoGroup(stringResource(R.string.info_group_audio), listOf(
                    stringResource(R.string.info_audio_outputs) to info.audioOutputs,
                    stringResource(R.string.info_audio_formats) to info.audioFormats,
                ))
                InfoGroup(stringResource(R.string.info_group_time_network), listOf(
                    stringResource(R.string.info_locale) to info.locale,
                    stringResource(R.string.info_auto_time) to infoBoolean(info.automaticTime),
                    stringResource(R.string.info_auto_zone) to infoBoolean(info.automaticTimeZone),
                    stringResource(R.string.info_uptime) to info.uptime,
                    stringResource(R.string.info_addresses) to info.networkAddresses,
                ))
                if (info.videoDecoders.isNotEmpty() || info.audioDecoders.isNotEmpty()) {
                    Text(stringResource(R.string.info_codecs_note), style = MaterialTheme.typography.bodySmall)
                    InfoGroup(stringResource(R.string.info_group_codecs), listOf(
                        stringResource(R.string.info_video_decoders) to info.videoDecoders,
                        stringResource(R.string.info_audio_decoders) to info.audioDecoders,
                    ))
                }
            }
        }
        Button(shape = MaterialTheme.shapes.medium, onClick = actions::refreshDeviceInfo, enabled = !state.busy) {
            Text(stringResource(R.string.info_refresh))
        }
    }
}

@Composable
private fun infoBoolean(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.info_yes)
    false -> stringResource(R.string.info_no)
    null -> ""
}

@Composable
private fun InfoGroup(title: String, rows: List<Pair<String, String>>) {
    if (rows.any { it.second.isNotBlank() }) {
        Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        rows.forEach { (label, value) -> InfoRow(label, value) }
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: Color = Color.Unspecified) {
    if (value.isNotBlank()) BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            CopyableText(value, style = MaterialTheme.typography.bodyMedium, color = color)
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall, color = color)
            SelectionContainer(Modifier.weight(0.65f)) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}
