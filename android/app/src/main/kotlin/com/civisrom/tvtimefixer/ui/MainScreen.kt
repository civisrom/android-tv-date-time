package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import kotlin.math.abs
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
import com.civisrom.tvtimefixer.diagnostics.Operation

/** Действия, которые экран запрашивает у владельца состояния. */
interface AppActions {
    fun connect(address: String)
    fun connectLoopback()
    fun disconnect()
    fun pairAndConnect(pairingAddress: String, code: String, connectAddress: String)
    fun checkNtpServer(server: String)
    fun applyNtpServer(server: String, force: Boolean = false)
    fun verifyDeviceTime()
    fun scanNtpServers()
    fun cancelNtpScan()
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
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var selectedEvent by rememberSaveable { mutableStateOf<Long?>(null) }
    var returnFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var origin by rememberSaveable { mutableStateOf("diagnostics-open") }
    // Код не попадает в savedInstanceState и сохраняется только на время
    // текущего процесса, в том числе при просмотре диагностики.
    var pairingCode by remember { mutableStateOf("") }
    LaunchedEffect(state.connected) { if (state.connected) pairingCode = "" }
    val holder = rememberSaveableStateHolder()
    if (showDiagnostics) {
        DiagnosticsScreen(mode, diagnostics, selectedEvent,
            onBack = {
                val available = when (origin) {
                    "crash-details" -> diagnostics.previousCrashId != null
                    "action-details" -> state.message != null && state.diagnosticEventId != null
                    "connection-details" -> state.connection is ConnectionState.Failed && state.diagnosticEventId != null
                    "ntp-details" -> state.ntpDiagnosticEventId != null
                    else -> true
                }
                showDiagnostics = false; returnFocus = if (available) origin else "diagnostics-open"
            }, onClear = onClearDiagnostics)
    } else holder.SaveableStateProvider("main") {
        MainContent(mode, state, actions, diagnostics, pairingCode, { pairingCode = it },
            onDiagnostics = { id, key ->
                selectedEvent = id; origin = key; returnFocus = null
                onRefreshDiagnostics(); showDiagnostics = true
            }, returnFocus = returnFocus, onFocusRestored = { returnFocus = null })
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
    TextButton(onClick = { onOpen(eventId, key) },
        modifier = Modifier.focusRequester(requester).testTag(key)) { Text(stringResource(title)) }
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
) {
    var pairingAddress by rememberSaveable { mutableStateOf("") }
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
            .padding(horizontal = if (mode == DeviceMode.TELEVISION) 32.dp else 16.dp, vertical = 24.dp)
            .testTag("main-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (mode == DeviceMode.TELEVISION) R.string.mode_television else R.string.mode_handheld),
            style = MaterialTheme.typography.bodyMedium)
        DiagnosticLink(null, "diagnostics-open", onDiagnostics, returnFocus, onFocusRestored, R.string.diagnostics_title)
        diagnostics.previousCrashId?.let { id ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.diagnostics_previous_crash))
                    DiagnosticLink(id, "crash-details", onDiagnostics, returnFocus, onFocusRestored)
                }
            }
        }
        ConnectionStatus(mode, state, actions)
        ExpandableSection(stringResource(R.string.discovery_title), "discovery", expanded = discoveryExpanded,
            onExpanded = { discoveryExpanded = it }) {
            DiscoverySection(state, actions, onPair = {
                pairingAddress = it; focusPairing = true
            })
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.operation?.let { Text(stringResource(R.string.operation_working, stringResource(it.labelRes()))) }
        }
        state.message?.let { message ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(message.res, *message.args.toTypedArray()))
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
        PairingSection(state, actions, pairingAddress, { pairingAddress = it },
            pairingCode, onPairingCode, pairingRequester)
        ExpandableSection(stringResource(R.string.usb_title), "usb", expanded = usbExpanded,
            onExpanded = { usbExpanded = it }) {
            UsbSection(state, actions)
        }
        if (state.connected) DeviceInfoSection(state, actions)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { if (onExpanded != null) onExpanded(!open) else localExpanded = !open },
            modifier = Modifier.testTag("section-$key").semantics { stateDescription = description },
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
            Text(stringResource(R.string.usb_setup_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions::refreshUsbDevices, enabled = !state.busy, modifier = Modifier.testTag("usb-refresh")) {
                Text(stringResource(R.string.usb_refresh))
            }
            when {
                state.usbScanFailed -> Text(stringResource(R.string.usb_scan_failed))
                state.usbAttachedCount == 0 && state.usbDevices.isEmpty() -> Text(stringResource(R.string.usb_none))
                state.usbDevices.isEmpty() -> Text(stringResource(R.string.usb_no_adb))
                else -> Text(stringResource(R.string.usb_detected, state.usbDevices.size))
            }
            if (state.usbDevices.isEmpty()) ExpandableSection(stringResource(R.string.usb_connection_help), "usb-help") {
                Text(stringResource(R.string.usb_help_host), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.usb_help_cable), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.usb_help_port), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.usb_help_debugging), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.usb_help_retry), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.usb_shield_hint), style = MaterialTheme.typography.bodySmall)
            }
            state.usbDevices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.label, style = MaterialTheme.typography.titleSmall)
                        // USB bus address distinguishes identical devices without reading serialNumber.
                        Text(device.deviceName, style = MaterialTheme.typography.bodySmall)
                        if (state.connectedUsb?.deviceName == device.deviceName) {
                            Text(stringResource(R.string.usb_connected), color = ConnectedColor)
                        } else {
                            Button(onClick = { actions.connectUsb(device) }, enabled = !state.busy) {
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
            Text(when (val connection = state.connection) {
                is ConnectionState.Connected -> stringResource(R.string.connect_state_connected, connection.address.toString())
                is ConnectionState.Connecting -> stringResource(R.string.connect_state_connecting, connection.address.toString())
                is ConnectionState.Checking -> stringResource(R.string.connect_state_checking, connection.address.toString())
                is ConnectionState.Failed -> stringResource(connection.reason.messageRes())
                ConnectionState.Disconnected -> stringResource(R.string.connect_state_disconnected)
            }, color = when (state.connection) {
                is ConnectionState.Connected -> ConnectedColor
                is ConnectionState.Connecting, is ConnectionState.Checking -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.error
            })
            if (state.connected) Button(onClick = actions::disconnect, enabled = !state.busy) {
                Text(stringResource(R.string.connect_disconnect))
            }
            NetworkAddressSection(mode, state, actions)
        }
    }
}

@Composable
private fun NetworkAddressSection(mode: DeviceMode, state: AppState, actions: AppActions) {
    var address by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = address, onValueChange = { address = it },
        label = { Text(stringResource(R.string.connect_address_hint)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("network-address"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { actions.connect(address) }, enabled = !state.busy) {
            Text(stringResource(R.string.connect_action))
        }
        if (mode == DeviceMode.TELEVISION) TextButton(onClick = actions::connectLoopback, enabled = !state.busy) {
            Text(stringResource(R.string.connect_try_loopback))
        }
    }
}

@Composable
private fun DiscoverySection(state: AppState, actions: AppActions, onPair: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // Без разрешения системный mDNS не вернёт ничего и не пожалуется:
            // отличить это от «в сети пусто» человек сам не сможет
            state.discoveryPermissionNeeded -> {
                Text(stringResource(R.string.discovery_permission_needed))
                Button(onClick = actions::requestDiscoveryPermission, enabled = !state.busy) {
                    Text(stringResource(R.string.discovery_grant_permission))
                }
            }
            !state.discoveryAvailable -> Text(stringResource(R.string.discovery_unavailable))
            state.discoverySearching && state.discovered.isEmpty() ->
                Text(stringResource(R.string.discovery_searching))
            state.discovered.isEmpty() -> Text(stringResource(R.string.discovery_empty))
        }
        // Про запрос авторизации сказано здесь, до подключения: увидев на
        // телевизоре окно с отпечатком ключа, человек должен понимать, что
        // это нормальный шаг, а не сбой. Формулировка та же, что и в
        // десктопной версии
        Text(
            stringResource(R.string.discovery_authorize_hint),
            style = MaterialTheme.typography.bodySmall,
        )
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
            Text(stringResource(R.string.discovery_found), color = ConnectedColor,
                style = MaterialTheme.typography.labelLarge)
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text("${device.address}  ·  ${stringResource(device.kind.labelRes())}")
            if (connected) {
                Text(
                    stringResource(R.string.discovery_connected),
                    color = ConnectedColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (awaitingPairing) {
                // Подключаться к такому устройству нечем: сперва код. Кнопка
                // переносит адрес в форму спаривания — раньше это приходилось
                // делать вручную, переписывая порт с экрана телевизора
                Button(onClick = { onPair(device.address.toString()) }, enabled = enabled) {
                    Text(stringResource(R.string.discovery_pair_action))
                }
            } else {
                Button(onClick = { onConnect(device.address.toString()) }, enabled = enabled) {
                    Text(stringResource(R.string.connect_action))
                }
            }
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
    val pairingSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pairing_hint), style = MaterialTheme.typography.bodySmall)
        if (!pairingSupported) {
            Text(stringResource(R.string.error_wireless_unsupported), style = MaterialTheme.typography.bodySmall)
        }
        // Самая частая причина неудачи: люди подставляют порт спаривания
        // в подключение, потому что оба показаны на одном экране телевизора
        Text(stringResource(R.string.pairing_port_warning), style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = pairingAddress,
            onValueChange = onPairingAddressChange,
            label = { Text(stringResource(R.string.pairing_address_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pairing-address"),
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCode,
            label = { Text(stringResource(R.string.pairing_code_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).testTag("pairing-code"),
        )
        OutlinedTextField(
            value = connectAddress,
            onValueChange = { connectAddress = it },
            label = { Text(stringResource(R.string.pairing_connect_address_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("pairing-connect-address"),
        )
        Button(
            onClick = { actions.pairAndConnect(pairingAddress, code, connectAddress) },
            enabled = !state.busy && pairingSupported,
            modifier = Modifier.testTag("pairing-connect"),
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
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showCountries by rememberSaveable { mutableStateOf(false) }
    var pickerExpanded by rememberSaveable { mutableStateOf(false) }
    val addressView = remember { BringIntoViewRequester() }
    val checkFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var selectionRequest by remember { mutableIntStateOf(0) }
    val onPick: (String) -> Unit = { server ->
        custom = server
        showCountries = false
        showAll = false
        selectionRequest += 1
    }
    LaunchedEffect(selectionRequest) {
        if (selectionRequest > 0) {
            withFrameNanos { }
            // clearFocus() на API 23 может вернуть фокус в поиск и открыть IME
            // снова. Переводим его на доступную без подключения кнопку.
            checkFocus.requestFocus()
            keyboard?.hide()
            addressView.bringIntoView()
        }
    }

    // Поиск идёт и по странам, и по альтернативным адресам: для человека это
    // один список серверов, а не две разные сущности
    // Сам поиск — чистая функция в data/NtpSearch.kt, чтобы он проверялся
    // тестами: промах здесь выглядит как «поиск не работает», и отличить его
    // от опечатки пользователя без теста невозможно
    val matches = remember(query) { searchNtpServers(query) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ntp_title), style = MaterialTheme.typography.titleMedium)
        // Заданный сервер выделен так же, как установленная связь: это второе
        // состояние, ради которого на экран смотрят
        val ntpIsSet = state.connected && state.currentNtpServer.isNotEmpty()
        Text(
            when {
                !state.connected -> stringResource(R.string.ntp_connect_first)
                ntpIsSet -> stringResource(R.string.ntp_current, state.currentNtpServer)
                else -> stringResource(R.string.ntp_current_unset)
            },
            color = if (ntpIsSet) ConnectedColor else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )

        Column(Modifier.bringIntoViewRequester(addressView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text(stringResource(R.string.ntp_custom_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("ntp-address"),
            )
            Text(
                stringResource(R.string.ntp_address_note),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.applyNtpServer(custom) },
                    enabled = state.connected && !state.busy && custom.isNotBlank(),
                    modifier = Modifier.testTag("ntp-apply"),
                ) {
                    Text(stringResource(R.string.ntp_apply))
                }
                TextButton(
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

        // Итог показывается здесь, а не в карточке вверху экрана: раздел
        // находится далеко внизу, и подтверждение там не видно
        state.ntpMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(message.res, *message.args.toTypedArray()),
                    // Успешная запись — зелёным, всё остальное здесь неудача:
                    // «не применено», «неверный адрес», «устройство сообщает
                    // другое значение»
                    color = if (message.res == R.string.ntp_applied) {
                        ConnectedColor
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Проверка идёт из сети телефона, а UDP-порт 123 закрывают и операторы,
        // и часть роутеров: полный запрет оставил бы человека вообще без
        // возможности задать сервер
        state.ntpRejected?.let { rejected ->
            Button(
                onClick = { actions.applyNtpServer(rejected, force = true) },
                enabled = state.connected && !state.busy,
                modifier = Modifier.testTag("ntp-apply-anyway"),
            ) {
                Text(stringResource(R.string.ntp_apply_anyway))
            }
        }

        state.ntpDiagnosticEventId?.let { id ->
            DiagnosticLink(id, "ntp-details", onDiagnostics, returnFocus, onFocusRestored)
        }
        if (state.connected) {
            TextButton(onClick = actions::verifyDeviceTime, enabled = !state.busy,
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
                label = { Text(stringResource(R.string.ntp_search_country)) },
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
                TextButton(onClick = { onPick(match.server) }, enabled = !state.busy) { Text(label) }
            }

            // Списки раскрываются только при пустом поиске: иначе на экране
            // оказались бы сразу и результаты поиска, и весь справочник
            if (query.isBlank()) {
                TextButton(onClick = { showCountries = !showCountries }, modifier = Modifier.testTag("ntp-countries")) {
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
                        TextButton(
                            onClick = { onPick(country.server) },
                            enabled = !state.busy,
                        ) {
                            Text("${country.code.uppercase()} · ${countryName(country)} · ${country.server}")
                        }
                    }
                }

                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.testTag("ntp-alternatives")) {
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
                        TextButton(
                            onClick = { onPick(server) },
                            enabled = !state.busy,
                        ) { Text(server) }
                    }
                }
            }

            NtpScanBlock(state, actions, onPick = onPick)
        }
    }
}

/** Вердикт по одному адресу: отвечает ли он как сервер времени. */
@Composable
private fun NtpCheckCard(check: NtpProbeResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(check.server, style = MaterialTheme.typography.bodyLarge)
            // Здесь строка не нажимается, поэтому и текст другой: обещать
            // нажатие там, где его нет, хуже, чем не показывать вовсе
            check.ipAddress?.takeIf { it != check.server }?.let {
                Text(stringResource(R.string.ntp_check_ip, it), style = MaterialTheme.typography.bodySmall)
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
            } else if (check.reachable) {
                Text(
                    stringResource(R.string.ntp_check_bad_clock),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    stringResource(R.string.ntp_check_failed, check.error.orEmpty()),
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
            if (check.server.isNotEmpty()) Text(stringResource(R.string.time_check_server, check.server))
            check.deviceTimeMillis?.let {
                val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(it))
                Text(stringResource(R.string.time_check_device_time, utc))
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

/** Подбор самого быстрого сервера — аналог автонастройки десктопной версии. */
@Composable
private fun NtpScanBlock(state: AppState, actions: AppActions, onPick: (String) -> Unit) {
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

    if (scan == null || scan.finished) {
        Button(onClick = actions::scanNtpServers, enabled = !state.busy, modifier = Modifier.testTag("ntp-scan-start")) {
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
            TextButton(onClick = { onPick(result.server) }, enabled = !state.busy) {
                Text(
                    stringResource(
                        R.string.ntp_scan_entry,
                        result.server,
                        result.avgRttMs ?: 0L,
                        result.successRate,
                    ),
                )
            }
            // IP показывается отдельной нажимаемой строкой: часть прошивок
            // не умеет резолвить имена, и тогда адрес нужно задавать числом.
            // Запрос DNS ради этого не делается — адрес уже известен от пробы.
            val ip = result.ipAddress
            if (ip != null && ip != result.server) {
                TextButton(onClick = { onPick(ip) }, enabled = !state.busy) {
                    Text(stringResource(R.string.ntp_scan_entry_ip, ip))
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
    Button(onClick = actions::cancelNtpScan, modifier = Modifier.testTag("ntp-scan-cancel")) {
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleMedium)
        state.deviceInfo?.let { info ->
            InfoRow(stringResource(R.string.info_model), info.model)
            InfoRow(stringResource(R.string.info_android), info.androidVersion)
            InfoRow(stringResource(R.string.info_ntp), info.currentNtpServer, color = ConnectedColor)
            InfoRow(stringResource(R.string.info_timezone), info.timezone)
            ExpandableSection(stringResource(R.string.info_more), "device-details") {
                InfoRow(stringResource(R.string.info_manufacturer), info.manufacturer)
                InfoRow(stringResource(R.string.info_api), info.apiLevel)
                InfoRow(stringResource(R.string.info_serial), info.serial)
                InfoRow(stringResource(R.string.info_cpu), info.cpuAbi)
                InfoRow(stringResource(R.string.info_cores), info.cpuCores)
                InfoRow(stringResource(R.string.info_locale), info.locale)
                InfoRow(stringResource(R.string.info_battery), info.batteryLevel)
                InfoRow(stringResource(R.string.info_ram), info.totalRam)
                InfoRow(stringResource(R.string.info_ram_free), info.availableRam)
                InfoRow(stringResource(R.string.info_screen), info.screenResolution)
                InfoRow(stringResource(R.string.info_density), info.screenDensity)
                InfoRow(stringResource(R.string.info_uptime), info.uptime)
                InfoRow(stringResource(R.string.info_kernel), info.kernelVersion)
            }
        }
        Button(onClick = actions::refreshDeviceInfo, enabled = !state.busy) {
            Text(stringResource(R.string.info_refresh))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: Color = Color.Unspecified) {
    if (value.isNotBlank()) BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall, color = color)
            Text(value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}
