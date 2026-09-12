package com.civisrom.tvtimefixer

import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.civisrom.tvtimefixer.adb.AdbClientFactory
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.DeviceConnector
import com.civisrom.tvtimefixer.adb.DeviceDiscovery
import com.civisrom.tvtimefixer.adb.KadbAdbClientFactory
import com.civisrom.tvtimefixer.adb.NsdDeviceDiscovery
import com.civisrom.tvtimefixer.adb.UsbDevices
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.adb.ACTION_USB_SYSTEM_STATE
import com.civisrom.tvtimefixer.adb.targetOrNull
import com.civisrom.tvtimefixer.data.NtpData
import com.civisrom.tvtimefixer.data.NtpProbe
import com.civisrom.tvtimefixer.data.NtpProbeFailure
import com.civisrom.tvtimefixer.data.NtpScanner
import com.civisrom.tvtimefixer.data.ScanProgress
import com.civisrom.tvtimefixer.data.Favorites
import com.civisrom.tvtimefixer.data.FavoriteDevice
import com.civisrom.tvtimefixer.data.FavoriteNtp
import com.civisrom.tvtimefixer.data.usableDeviceSerial
import com.civisrom.tvtimefixer.data.isUsable
import com.civisrom.tvtimefixer.device.DeviceRepository
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import com.civisrom.tvtimefixer.device.DeviceTimeVerifier
import com.civisrom.tvtimefixer.net.UdpSntpClient
import com.civisrom.tvtimefixer.ui.AppActions
import com.civisrom.tvtimefixer.ui.AppState
import com.civisrom.tvtimefixer.ui.MainScreen
import com.civisrom.tvtimefixer.ui.UiMessage
import com.civisrom.tvtimefixer.ui.toUiMessage
import com.civisrom.tvtimefixer.ui.messageRes
import com.civisrom.tvtimefixer.ui.rejectionMessageRes
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.Outcome
import com.civisrom.tvtimefixer.diagnostics.DiagnosticIssue
import com.civisrom.tvtimefixer.diagnostics.DiagnosticTransport
import com.civisrom.tvtimefixer.adb.DeviceTarget
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import com.civisrom.tvtimefixer.device.TimeZoneRepository
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.device.TimeZoneFailure
import com.civisrom.tvtimefixer.device.TimeZoneRestoration
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import com.civisrom.tvtimefixer.diagnostics.OperationTrace
import com.civisrom.tvtimefixer.terminal.*
import com.civisrom.tvtimefixer.ui.TerminalActions

class MainActivity : ComponentActivity() {

    private val factory: AdbClientFactory = KadbAdbClientFactory()
    private val usb by lazy { UsbDevices(this) }
    private val journal get() = (application as TimeFixerApplication).diagnostics
    private val connector by lazy {
        DeviceConnector(factory, usbConnect = usb::connect, onFailure = { target, error ->
            val operation = when {
                error.reason.name.startsWith("PAIRING") || error.reason == ConnectionError.TLS_FAILED -> Operation.PAIR
                target is UsbDeviceAddress -> Operation.CONNECT_USB
                else -> Operation.CONNECT_NETWORK
            }
            journal.record(operation, Outcome.FAILED, diagnosticTransport(target), reason = error.reason, error = error)
        })
    }
    private var discovery: DeviceDiscovery? = null
    private var permissionsRequested = false
    private var actionJob: Job? = null
    private var actionGeneration = 0
    private val deviceOperations = Mutex()
    private var usbReceiverRegistered = false
    private val favoritesStore get() = (application as TimeFixerApplication).favorites
    private val terminal = TerminalSession()
    private val terminalFiles by lazy { TerminalFiles(File(filesDir, "terminal-documents")) }
    private var terminalFileBusy by mutableStateOf(false)
    private var terminalFileMessage by mutableStateOf<Int?>(null)
    private var exportDocument: String? = null

    private val importDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) copyTerminalDocument {
            for (uri in uris) {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "document.bin"
                val local = terminalFiles.uniqueName(name)
                contentResolver.openInputStream(uri)?.use { input ->
                    terminalFiles.receive(local) { output -> input.copyTo(output) }
                } ?: throw java.io.IOException("Document unavailable")
            }
        }
    }
    private val exportDocuments = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = exportDocument
        exportDocument = null
        if (uri != null && name != null) copyTerminalDocument {
            terminalFiles.resolve(name).inputStream().use { input ->
                contentResolver.openOutputStream(uri, "wt")?.use { input.copyTo(it) }
                    ?: throw java.io.IOException("Document unavailable")
            }
        }
    }

    private fun copyTerminalDocument(block: () -> Unit) {
        if (terminalFileBusy || terminal.state.value.running || state.busy) return
        terminalFileBusy = true; terminalFileMessage = null
        lifecycleScope.launch {
            try {
                runInterruptible(Dispatchers.IO, block)
                terminalFileMessage = R.string.terminal_file_done
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { terminalFileMessage = R.string.terminal_file_failed
            } finally {
                terminalFileBusy = false
                refreshTerminalFiles()
            }
        }
    }

    private suspend fun refreshTerminalFiles() {
        val names = withContext(Dispatchers.IO) { terminalFiles.list().map { it.name } }
        terminal.refreshFiles(names)
    }

    private val terminalActions = object : TerminalActions {
        override fun edit(command: String) = terminal.edit(command)
        override fun clearOutput() = terminal.clearOutput()
        override fun clearHistory() = terminal.clearHistory()
        override fun stop() { if (terminal.state.value.running) actionJob?.cancel() }
        override fun importFiles() {
            if (terminalFileBusy || state.busy) return
            try { importDocuments.launch(arrayOf("*/*")) }
            catch (_: android.content.ActivityNotFoundException) { terminalFileMessage = R.string.terminal_file_unavailable }
        }
        override fun exportFile(name: String) {
            if (terminalFileBusy || state.busy) return
            exportDocument = name
            try { exportDocuments.launch(name) }
            catch (_: android.content.ActivityNotFoundException) { exportDocument = null; terminalFileMessage = R.string.terminal_file_unavailable }
        }

        override fun removeFile(name: String) {
            copyTerminalDocument { terminalFiles.remove(name) }
        }

        override fun run() {
            if (state.busy || terminalFileBusy) return
            if (state.connection.targetOrNull() is com.civisrom.tvtimefixer.data.DeviceAddress && !networkAllowed()) return
            val command = terminal.start(state.connection.takeIf { state.connected }?.targetOrNull()?.toString()
                ?: getString(R.string.terminal_unknown_target), state.deviceName) ?: return
            val generation = ++actionGeneration
            state = state.copy(busy = true, operation = Operation.TERMINAL, message = null, diagnosticEventId = null)
            actionJob = lifecycleScope.launch {
                try {
                    deviceOperations.withLock {
                        val adb = command as? TerminalCommand.Adb
                        when (adb?.name) {
                            "connect" -> {
                                if (adb.arguments.size != 1) throw TerminalException(TerminalProblem.ARGUMENTS)
                                if (com.civisrom.tvtimefixer.data.parseDeviceAddress(adb.arguments.single()) == null) {
                                    throw TerminalException(TerminalProblem.ARGUMENTS)
                                }
                                if (!networkAllowed()) throw TerminalException(TerminalProblem.CONNECTION)
                                val connection = runInterruptible(Dispatchers.IO) { connector.connect(adb.arguments.single()) }
                                state = state.connectionLost().copy(connection = connection)
                                if (connection !is ConnectionState.Connected) {
                                    val reason = (connection as? ConnectionState.Failed)?.reason
                                    terminal.append(getString(R.string.terminal_connect_failed,
                                        getString(reason?.messageRes() ?: R.string.terminal_connection_error)) + "\n", true)
                                    throw TerminalException(TerminalProblem.CONNECTION)
                                }
                                terminal.append(getString(R.string.terminal_connect_success, connection.address.toString()) + "\n")
                                val name = runInterruptible(Dispatchers.IO) {
                                    val client = connector.activeClient ?: throw TerminalException(TerminalProblem.CONNECTION)
                                    DeviceRepository(client).readDeviceName()
                                }
                                state = state.copy(deviceName = name)
                                terminal.identifyTarget(connection.address.toString(), name)
                                if (name.isNotBlank()) terminal.append(getString(R.string.terminal_device_name, name) + "\n")
                                terminal.finish(0)
                            }
                            "disconnect" -> {
                                if (adb.arguments.size > 1 || (adb.arguments.isNotEmpty() &&
                                    com.civisrom.tvtimefixer.data.parseDeviceAddress(adb.arguments.single()) != state.connectedAddress)) {
                                    throw TerminalException(TerminalProblem.ARGUMENTS)
                                }
                                runInterruptible(Dispatchers.IO) { connector.disconnect() }
                                state = state.connectionLost(); terminal.finish(0)
                            }
                            "devices", "get-state", "get-serialno" -> {
                                if (adb.arguments.isNotEmpty() && !(adb.name == "devices" && adb.arguments == listOf("-l"))) {
                                    throw TerminalException(TerminalProblem.ARGUMENTS)
                                }
                                val connection = runInterruptible(Dispatchers.IO) { connector.checkConnection() }
                                if (connection is ConnectionState.Connected) {
                                    val value = when (adb.name) {
                                        "devices" -> "${connection.address}\tdevice\n"
                                        "get-serialno" -> if (connection.address is com.civisrom.tvtimefixer.data.DeviceAddress) {
                                            connection.address.toString()
                                        } else runInterruptible(Dispatchers.IO) {
                                            connector.activeClient?.shell("getprop ro.serialno")?.output
                                                ?: throw TerminalException(TerminalProblem.CONNECTION)
                                        }
                                        else -> "device\n"
                                    }
                                    terminal.append(value)
                                } else {
                                    state = state.connectionLost()
                                    if (adb.name != "devices") throw TerminalException(TerminalProblem.CONNECTION)
                                }
                                terminal.finish(0)
                            }
                            else -> {
                                if (!state.connected) throw TerminalException(TerminalProblem.CONNECTION)
                                // Arbitrary shell commands can change any cached setting.
                                state = state.connectionLost().copy(connection = state.connection, deviceName = state.deviceName)
                                val exit = runInterruptible(Dispatchers.IO) {
                                    val client = connector.activeClient ?: throw TerminalException(TerminalProblem.CONNECTION)
                                    TerminalExecutor(terminalFiles, terminal).execute(client, command)
                                }
                                if (adb?.name in setOf("reboot", "root", "unroot", "usb", "tcpip")) {
                                    runInterruptible(Dispatchers.IO) { connector.disconnect() }
                                    state = state.connectionLost()
                                }
                                terminal.finish(exit)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    terminal.fail(e)
                    withContext(NonCancellable + Dispatchers.IO) { connector.disconnect() }
                    if (generation == actionGeneration) state = state.connectionLost()
                    throw e
                } catch (e: Exception) {
                    terminal.fail(e)
                    if (withContext(Dispatchers.IO) { connector.activeClient == null }) state = state.connectionLost()
                } finally {
                    if (generation == actionGeneration) state = state.copy(busy = false, operation = null)
                    refreshTerminalFiles()
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) {
                val selected = state.connection.targetOrNull() as? UsbDeviceAddress
                if (selected?.deviceName == device.deviceName) {
                    val generation = ++actionGeneration
                    actionJob?.cancel()
                    val event = journal.record(Operation.USB_DETACHED, Outcome.FAILED,
                        DiagnosticTransport.USB, reason = ConnectionError.USB_DISCONNECTED)
                    state = state.connectionLost().copy(busy = true,
                        operation = Operation.DISCONNECT, diagnosticEventId = event,
                        deviceInfo = null, ntpMessage = null, ntpCheck = null, ntpDiagnosticEventId = null,
                        timeCheck = null, timeDiagnosticEventId = null,
                        timeZoneResult = null, timeZoneDiagnosticEventId = null,
                        message = UiMessage(R.string.error_usb_disconnected))
                    lifecycleScope.launch {
                        // Даже releaseInterface/close могут ждать kernel I/O.
                        // Освобождаем USB вне UI, до следующего подключения.
                        runInterruptible(Dispatchers.IO) {
                            usb.detached(device.deviceName)
                            connector.disconnect()
                        }
                        if (generation == actionGeneration) state = state.copy(busy = false, operation = null)
                    }
                } else {
                    lifecycleScope.launch(Dispatchers.IO) { usb.detached(device.deviceName) }
                }
            }
            refreshUsbList()
        }
    }

    private val ntpProbe = NtpProbe(UdpSntpClient())
    private val ntpScanner = NtpScanner(NtpProbe(UdpSntpClient(), attempts = 5, pause = { Thread.sleep(1000) }))
    private val timeVerifier = DeviceTimeVerifier(UdpSntpClient(), SystemClock::elapsedRealtime)
    private var scanJob: Job? = null

    /**
     * Состояние экрана живёт в Activity, а не внутри setContent: `mutableStateOf`
     * в теле composable-лямбды пересоздавался бы при каждой рекомпозиции, то есть
     * стирался бы при первом же собственном изменении.
     */
    private var state by mutableStateOf(AppState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            startDiscovery()
        } else {
            // LAN denial on target 37+ also blocks direct connections.
            state = state.copy(discoveryPermissionNeeded = true)
        }
    }

    private val actions = object : AppActions {

        private fun run(operation: Operation, block: suspend (OperationTrace) -> AppState) {
            if (state.busy) return
            if (operation in setOf(Operation.CONNECT_NETWORK, Operation.PAIR, Operation.CHECK_NTP,
                    Operation.APPLY_NTP, Operation.CHECK_TIME) && !networkAllowed()) return
            if (operation in setOf(Operation.READ_DEVICE, Operation.APPLY_TIME_ZONE) &&
                state.connection.targetOrNull() is com.civisrom.tvtimefixer.data.DeviceAddress && !networkAllowed()) return
            val generation = ++actionGeneration
            if (operation in setOf(Operation.CONNECT_NETWORK, Operation.CONNECT_USB, Operation.PAIR)) {
                state = state.connectionLost()
            }
            val started = System.nanoTime()
            val transport = when (operation) {
                Operation.CONNECT_USB -> DiagnosticTransport.USB
                Operation.CONNECT_NETWORK, Operation.PAIR -> DiagnosticTransport.NETWORK
                else -> diagnosticTransport(state.connection.targetOrNull())
            }
            val ntpAction = operation == Operation.CHECK_NTP || operation == Operation.APPLY_NTP
            val timeAction = operation == Operation.CHECK_TIME
            val zoneAction = operation == Operation.APPLY_TIME_ZONE
            val resetTime = operation in setOf(Operation.CONNECT_NETWORK, Operation.CONNECT_USB,
                Operation.PAIR, Operation.APPLY_NTP, Operation.CHECK_TIME, Operation.READ_DEVICE, Operation.APPLY_TIME_ZONE)
            val resetZone = zoneAction || operation in setOf(Operation.CONNECT_NETWORK, Operation.CONNECT_USB,
                Operation.PAIR, Operation.READ_DEVICE)
            state = state.copy(busy = true, operation = operation, diagnosticEventId = null,
                timeZoneResult = if (resetZone) null else state.timeZoneResult,
                timeZoneDiagnosticEventId = if (resetZone) null else state.timeZoneDiagnosticEventId,
                timeCheck = if (resetTime) null else state.timeCheck,
                timeDiagnosticEventId = if (resetTime) null else state.timeDiagnosticEventId,
                ntpDiagnosticEventId = if (ntpAction) null else state.ntpDiagnosticEventId)
            journal.record(operation, Outcome.STARTED, transport)
            actionJob = lifecycleScope.launch {
                val trace = OperationTrace()
                try {
                    // Нажатие во время фоновой проверки ждёт её завершения,
                    // не теряется и не читает тот же ADB-транспорт одновременно.
                    val result = deviceOperations.withLock { block(trace) }
                    val failure = result.connection as? ConnectionState.Failed
                    val failed = when (operation) {
                        Operation.CONNECT_NETWORK, Operation.CONNECT_USB, Operation.PAIR -> !result.connected
                        Operation.CHECK_NTP -> result.ntpCheck?.isUsable() != true
                        Operation.APPLY_NTP -> result.ntpMessage?.res !in listOf(R.string.ntp_applied, R.string.ntp_default_applied)
                        Operation.CHECK_TIME -> result.timeCheck?.status != DeviceTimeStatus.MATCH
                        Operation.APPLY_TIME_ZONE -> result.timeZoneResult !is TimeZoneUpdateResult.Applied
                        Operation.READ_DEVICE -> result.diagnosticEventId != null || !result.connected || result.deviceInfo == null
                        else -> false
                    }
                    val existing = if (zoneAction) result.timeZoneDiagnosticEventId else if (timeAction) result.timeDiagnosticEventId else if (ntpAction) result.ntpDiagnosticEventId
                        else failure?.diagnosticId ?: result.diagnosticEventId
                    val event = if (failed && existing != null) existing else journal.record(operation,
                        if (failed) Outcome.FAILED else Outcome.SUCCESS, transport,
                        durationMs = (System.nanoTime() - started) / 1_000_000, trace = trace,
                        reason = failure?.reason ?: ConnectionError.UNREACHABLE.takeIf {
                            failed && !result.connected && operation == Operation.READ_DEVICE
                        }, issue = if (!failed) null else when {
                            zoneAction -> (result.timeZoneResult as? TimeZoneUpdateResult.Failed)?.diagnosticIssue()
                            timeAction -> result.timeCheck?.diagnosticIssue()
                            ntpAction && result.ntpCheck?.failure == NtpProbeFailure.INVALID_ADDRESS -> DiagnosticIssue.INVALID_NTP
                            ntpAction && result.ntpCheck?.reachable == false -> DiagnosticIssue.NTP_UNREACHABLE
                            ntpAction && result.ntpCheck?.isUsable() == false -> DiagnosticIssue.NTP_UNUSABLE
                            result.ntpMessage?.res == R.string.ntp_not_confirmed -> DiagnosticIssue.NTP_NOT_CONFIRMED
                            result.ntpMessage?.res == R.string.ntp_invalid -> DiagnosticIssue.INVALID_NTP
                            else -> null
                        })
                    if (generation == actionGeneration) state = result.copy(
                        diagnosticEventId = if (!ntpAction && !timeAction && !zoneAction && failed) event else result.diagnosticEventId,
                        timeZoneDiagnosticEventId = if (zoneAction && failed) event else result.timeZoneDiagnosticEventId,
                        ntpDiagnosticEventId = if (ntpAction && failed) event else result.ntpDiagnosticEventId,
                        timeDiagnosticEventId = if (timeAction && failed) event else result.timeDiagnosticEventId,
                    ).withLatestBackground(state)
                } catch (e: CancellationException) {
                    journal.record(operation, Outcome.CANCELLED, transport, trace = trace)
                    throw e
                } catch (e: Exception) {
                    val event = journal.record(operation, Outcome.FAILED, transport,
                        durationMs = (System.nanoTime() - started) / 1_000_000, error = e, trace = trace)
                    if (generation == actionGeneration) state = if (zoneAction) state.copy(
                        timeZoneResult = TimeZoneUpdateResult.Failed(TimeZoneFailure.READ_STATE), timeZoneDiagnosticEventId = event,
                    ) else if (timeAction) state.copy(
                        timeCheck = DeviceTimeCheck(DeviceTimeStatus.DEVICE_UNAVAILABLE), timeDiagnosticEventId = event,
                    ) else if (ntpAction) state.copy(
                        ntpMessage = UiMessage(R.string.operation_failed_hint), ntpDiagnosticEventId = event,
                    ) else state.copy(message = UiMessage(R.string.operation_failed_hint), diagnosticEventId = event)
                } finally {
                    if (generation == actionGeneration) state = state.copy(busy = false, operation = null)
                }
            }
        }

        override fun connect(address: String) = run(Operation.CONNECT_NETWORK) { trace ->
            val result = runInterruptible(Dispatchers.IO) { connector.connect(address) }
            state.copy(connection = result, message = null).withDeviceData(trace)
        }

        override fun connectFavorite(favorite: FavoriteDevice) = run(Operation.CONNECT_NETWORK) { trace ->
            val stored = state.favorites.devices.firstOrNull { it == favorite } ?: return@run state
            val result = runInterruptible(Dispatchers.IO) { connector.connect(stored.address.toString()) }
            val connected = state.copy(connection = result, message = null).withDeviceData(trace)
            if (!connected.connected || stored.matches(connected.deviceInfo)) connected else {
                runInterruptible(Dispatchers.IO) { connector.disconnect() }
                connected.connectionLost().copy(message = UiMessage(R.string.favorite_identity_changed))
            }
        }

        override fun saveCurrentDevice(name: String) {
            val address = state.connectedAddress ?: return
            val info = state.deviceInfo ?: return
            if (!usableDeviceSerial(info.serial)) {
                state = state.copy(message = UiMessage(R.string.favorite_identity_missing))
                return
            }
            changeFavorites { old -> old.copy(devices = old.devices.filterNot { it.serial == info.serial } +
                FavoriteDevice(name.trim(), address, info.serial, info.model)) }
        }

        override fun updateFavoriteDevice(favorite: FavoriteDevice) = changeFavorites { old ->
            require(old.devices.any { it.serial == favorite.serial && it.model == favorite.model })
            old.copy(devices = old.devices.map { if (it.serial == favorite.serial) favorite else it })
        }

        override fun removeFavoriteDevice(serial: String) = changeFavorites {
            it.copy(devices = it.devices.filterNot { device -> device.serial == serial })
        }

        override fun saveFavoriteNtp(name: String, server: String) = changeFavorites { old ->
            val canonical = server.trim().lowercase(java.util.Locale.ROOT)
            old.copy(servers = old.servers.filterNot { it.server.equals(canonical, ignoreCase = true) } +
                FavoriteNtp(name.trim(), canonical))
        }

        override fun removeFavoriteNtp(server: String) = changeFavorites {
            it.copy(servers = it.servers.filterNot { entry -> entry.server == server })
        }

        override fun openSetupSettings(action: String) {
            if (!openLocalSettings(this@MainActivity, action)) {
                state = state.copy(message = UiMessage(R.string.setup_settings_unavailable))
            }
        }

        override fun connectLoopback() = run(Operation.CONNECT_NETWORK) { trace ->
            val result = runInterruptible(Dispatchers.IO) { connector.connectLoopback() }
            state.copy(connection = result, message = null).withDeviceData(trace)
        }

        override fun disconnect() = run(Operation.DISCONNECT) {
            runInterruptible(Dispatchers.IO) { connector.disconnect() }
            state.connectionLost().copy(message = null, diagnosticEventId = null)
        }

        override fun refreshUsbDevices() = refreshUsbList(report = true)

        override fun connectUsb(address: UsbDeviceAddress) = run(Operation.CONNECT_USB) { trace ->
            runInterruptible(Dispatchers.IO) { connector.disconnect() }
            state = state.copy(connection = ConnectionState.Connecting(address),
                deviceInfo = null, ntpMessage = null,
                ntpCheck = null,
                message = UiMessage(R.string.usb_authorize_hint))
            state = state.copy(operation = Operation.USB_PERMISSION)
            journal.record(Operation.USB_PERMISSION, Outcome.STARTED, DiagnosticTransport.USB)
            val permissionStarted = System.nanoTime()
            val granted = try { usb.requestPermission(address) } catch (e: CancellationException) {
                journal.record(Operation.USB_PERMISSION, Outcome.CANCELLED, DiagnosticTransport.USB)
                throw e
            }
            val reason = if (granted) null else if (usb.list().any { it.deviceName == address.deviceName })
                ConnectionError.USB_PERMISSION_DENIED else ConnectionError.USB_DISCONNECTED
            val permissionEvent = journal.record(Operation.USB_PERMISSION,
                if (granted) Outcome.SUCCESS else Outcome.FAILED, DiagnosticTransport.USB,
                durationMs = (System.nanoTime() - permissionStarted) / 1_000_000, reason = reason)
            state = state.copy(operation = Operation.CONNECT_USB)
            if (reason != null) {
                return@run state.copy(connection = ConnectionState.Failed(address, reason, permissionEvent), message = null)
            }
            val result = runInterruptible(Dispatchers.IO) { connector.connectUsb(address) }
            state.copy(connection = result, message = null).withDeviceData(trace)
        }

        override fun pairAndConnect(
            pairingAddress: String,
            code: String,
            connectAddress: String,
        ) = run(Operation.PAIR) { trace ->
            // Спаривание и следующее за ним подключение оба ходят в сеть, а
            // connect внутри блокирующий: на главном потоке это NetworkOnMainThread
            val result = withContext(Dispatchers.IO) {
                connector.pairAndConnect(pairingAddress, code, connectAddress)
            }
            state.copy(connection = result, message = null).withDeviceData(trace)
        }

        override fun checkNtpServer(server: String) = run(Operation.CHECK_NTP) { trace ->
            state = state.copy(ntpMessage = null, ntpCheck = null)
            val result = runInterruptible(Dispatchers.IO) { ntpProbe.test(server) }
            trace.ntp(result)
            state.copy(ntpCheck = result)
        }

        override fun applyNtpServer(server: String, allowUnverified: Boolean) = changeNtp(server, allowUnverified)

        override fun resetNtpServer() = changeNtp(null)

        override fun undoNtpServer() {
            val change = state.ntpChange ?: return
            changeNtp(change.previous, allowUnverified = true, undo = change)
        }

        private fun changeNtp(server: String?, allowUnverified: Boolean = false,
            undo: NtpUpdateResult.Applied? = null,
        ) = run(Operation.APPLY_NTP) { trace ->
            state = state.copy(ntpMessage = null)
            val client = connector.activeClient ?: return@run state.connectionLost().copy(
                message = UiMessage(R.string.error_unreachable))
            var reference: String? = null
            val applied = runInterruptible(Dispatchers.IO) {
                val check = if (server != null && undo == null && !allowUnverified) ntpProbe.test(server) else null
                check?.let(trace::ntp)
                if (check != null && !check.isUsable()) return@runInterruptible state.copy(
                    ntpCheck = check, ntpMessage = UiMessage(R.string.ntp_check_rejected,
                        listOf(getString(check.rejectionMessageRes()))))
                var failureId: Long? = null
                val repository = DeviceRepository(trace.client(client)) { error ->
                    failureId = journal.record(Operation.APPLY_NTP, Outcome.FAILED,
                        diagnosticTransport(state.connection.targetOrNull()), error = error, trace = trace)
                }
                val previous = repository.currentNtpServer()
                reference = server?.takeUnless { it == "null" } ?: previous.takeUnless { it == "null" }
                val before = timeVerifier.verify(trace.client(client), referenceOverride = reference, onFailure = trace::exception)
                val result = when {
                    undo != null -> repository.undoNtpServer(undo)
                    server == null -> repository.resetNtpServer()
                    else -> repository.setNtpServer(server)
                }
                trace.ntpUpdate(result)
                val actual = if (result is NtpUpdateResult.Applied) result.server
                    else runCatching { repository.currentNtpServer() }.getOrDefault("")
                state.copy(ntpCheck = check,
                    ntpMessage = if (result is NtpUpdateResult.Failed) UiMessage(R.string.operation_failed_hint) else result.toUiMessage(),
                    ntpDiagnosticEventId = failureId,
                    ntpChange = (result as? NtpUpdateResult.Applied) ?: state.ntpChange,
                    ntpBeforeTime = if (result is NtpUpdateResult.Applied) before else state.ntpBeforeTime,
                    deviceInfo = (state.deviceInfo ?: com.civisrom.tvtimefixer.device.DeviceInfo()).copy(currentNtpServer = actual))
            }
            if (applied.ntpMessage?.res !in listOf(R.string.ntp_applied, R.string.ntp_default_applied)) return@run applied
            // Запись уже подтверждена. Сравнение часов — отдельное наблюдение с контроллера.
            state = applied.copy(operation = Operation.CHECK_TIME).withLatestBackground(state)
            val clockTrace = OperationTrace()
            var verified = state.withDeviceTime(clockTrace, reference)
            // Автовремя может обновиться позже записи. Небольшое число повторов,
            // без скрытой перезагрузки устройства и без обещания смены системного источника.
            repeat(2) {
                if (verified.timeCheck?.status == DeviceTimeStatus.MISMATCH &&
                    applied.ntpChange?.activation == com.civisrom.tvtimefixer.device.NtpActivation.NEXT_REFRESH &&
                    applied.ntpChange.automaticTime == true) {
                    delay(1500)
                    verified = state.withDeviceTime(clockTrace, reference)
                }
            }
            val failed = verified.timeCheck?.status != DeviceTimeStatus.MATCH
            val event = journal.record(Operation.CHECK_TIME, if (failed) Outcome.FAILED else Outcome.SUCCESS,
                diagnosticTransport(state.connection.targetOrNull()), issue = verified.timeCheck?.diagnosticIssue(), trace = clockTrace)
            verified.copy(timeDiagnosticEventId = event.takeIf { failed })
        }

        override fun verifyDeviceTime() = run(Operation.CHECK_TIME) { trace -> state.withDeviceTime(trace) }

        override fun applyTimeZone(zoneId: String) = run(Operation.APPLY_TIME_ZONE) { trace ->
            runInterruptible(Dispatchers.IO) {
                val client = connector.activeClient
                if (!state.connected || client == null) return@runInterruptible state.connectionLost().copy(
                    timeZoneResult = TimeZoneUpdateResult.Failed(TimeZoneFailure.READ_STATE),
                    message = UiMessage(R.string.error_unreachable),
                )
                var error: Exception? = null
                val result = TimeZoneRepository(trace.client(client), onFailure = { error = it }).setTimeZone(zoneId)
                trace.timeZone(result)
                val failure = result as? TimeZoneUpdateResult.Failed
                val event = failure?.let {
                    journal.record(Operation.APPLY_TIME_ZONE, Outcome.FAILED,
                        diagnosticTransport(state.connection.targetOrNull()), issue = it.diagnosticIssue(), error = error, trace = trace)
                }
                val actual = when (result) {
                    is TimeZoneUpdateResult.Applied -> result.zoneId
                    is TimeZoneUpdateResult.Failed -> result.actualZone
                }
                state.copy(timeZoneResult = result, timeZoneDiagnosticEventId = event,
                    deviceInfo = state.deviceInfo?.let { info ->
                        info.copy(
                            timezone = if (actual != null || failure?.restoration == TimeZoneRestoration.UNCONFIRMED)
                                actual.orEmpty() else info.timezone,
                            automaticTimeZone = DeviceRepository(trace.client(client))
                                .automaticTimeZoneEnabled(info.apiLevel.toIntOrNull()),
                        )
                    })
            }
        }

        override fun scanNtpServers() {
            if (scanJob?.isActive == true) return
            if (!networkAllowed()) return
            state = state.copy(ntpMessage = null, ntpCheck = null, ntpDiagnosticEventId = null,
                ntpScan = ScanProgress(0, NtpData.allServers.size, emptyList()))
            journal.record(Operation.SCAN_NTP, Outcome.STARTED)
            val started = System.nanoTime()
            scanJob = lifecycleScope.launch {
                var lastProgress = ScanProgress(0, NtpData.allServers.size, emptyList())
                try {
                    ntpScanner.scan(NtpData.allServers).collect { progress ->
                        lastProgress = progress
                        state = state.copy(ntpScan = progress)
                    }
                    journal.record(Operation.SCAN_NTP, Outcome.SUCCESS,
                        durationMs = (System.nanoTime() - started) / 1_000_000,
                        trace = OperationTrace().apply { scan(lastProgress) })
                } catch (e: CancellationException) {
                    journal.record(Operation.SCAN_NTP, Outcome.CANCELLED,
                        trace = OperationTrace().apply { scan(lastProgress) })
                    throw e
                } catch (e: Exception) {
                    val event = journal.record(Operation.SCAN_NTP, Outcome.FAILED, error = e)
                    state = state.copy(ntpScan = null, ntpDiagnosticEventId = event,
                        ntpMessage = UiMessage(R.string.operation_failed_hint))
                }
            }
        }

        override fun cancelNtpScan() {
            scanJob?.cancel()
            scanJob = null
            // Найденное не выбрасываем: перебор останавливают обычно именно
            // потому, что подходящий сервер уже виден в списке
            state = state.copy(ntpScan = state.ntpScan?.copy(cancelled = true))
        }

        override fun clearNtpScanResults() {
            // Можно выбрать и промежуточный результат. Останавливаем поиск,
            // чтобы следующий ответ не вернул уже убранный список.
            scanJob?.cancel()
            scanJob = null
            state = state.copy(ntpScan = null)
        }

        override fun refreshDeviceInfo() = run(Operation.READ_DEVICE) { trace -> state.withDeviceData(trace) }

        override fun requestDiscoveryPermission() = requestDiscoveryPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportDocument = savedInstanceState?.getString("terminalExportDocument")
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_SYSTEM_STATE)
        }
        ContextCompat.registerReceiver(this, usbReceiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        usbReceiverRegistered = true
        refreshUsbList()
        val mode = detectDeviceMode(this)

        setContent {
            MaterialTheme {
                Surface {
                    val diagnostics by journal.snapshot.collectAsState()
                    val terminalState by terminal.state.collectAsState()
                    MainScreen(mode = mode, state = state, actions = actions, diagnostics = diagnostics,
                        onRefreshDiagnostics = journal::refresh, onClearDiagnostics = journal::clear,
                        terminal = terminalState, terminalActions = terminalActions,
                        terminalFileBusy = terminalFileBusy, terminalFileMessage = terminalFileMessage)
                }
            }
        }
        lifecycleScope.launch { refreshTerminalFiles() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var returning = true
                while (isActive) {
                    // Пользовательские команды имеют приоритет перед очередной проверкой.
                    if (state.busy) {
                        delay(200)
                        continue
                    }
                    checkDeviceConnection(returning)
                    returning = false
                    delay(10_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Системный USB intent только обновляет список. ADB запускается кнопкой.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) refreshUsbList(report = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Only a pending document name; terminal commands and output remain memory-only.
        outState.putString("terminalExportDocument", exportDocument)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        state = state.copy(localSetup = readLocalSetup(this))
        if (!state.favoritesReady && !state.favoritesBusy) loadFavorites()
        val connected = state.connection as? ConnectionState.Connected
        if (connected != null && !state.busy) {
            state = state.copy(connection = ConnectionState.Checking(connected.address))
        }
        refreshUsbList()
        if (missingDiscoveryPermissions().isEmpty()) {
            startDiscovery()
        } else {
            runCatching { discovery?.stop() }
            state = state.copy(discoveryPermissionNeeded = true)
            if (state.connection.targetOrNull() is com.civisrom.tvtimefixer.data.DeviceAddress) {
                actionGeneration++
                actionJob?.cancel()
                state = state.copy(busy = false)
                actions.disconnect()
            }
            if (!permissionsRequested) {
                // Один раз за жизнь Activity: после отказа система отвечает
                // отказом молча, и дёргать её при каждом возврате на экран
                // бессмысленно — дальше решает кнопка «Разрешить»
                permissionsRequested = true
                requestDiscoveryPermissions()
            }
        }
    }

    private fun loadFavorites() {
        state = state.copy(favoritesBusy = true)
        lifecycleScope.launch {
            try {
                val loaded = runInterruptible(Dispatchers.IO) { favoritesStore.read() }
                state = state.copy(favorites = loaded, favoritesReady = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(message = UiMessage(R.string.favorites_storage_failed))
            } finally {
                state = state.copy(favoritesBusy = false)
            }
        }
    }

    private fun changeFavorites(change: (Favorites) -> Favorites) {
        if (!state.favoritesReady || state.favoritesBusy) return
        state = state.copy(favoritesBusy = true)
        lifecycleScope.launch {
            try {
                val updated = change(state.favorites)
                runInterruptible(Dispatchers.IO) { favoritesStore.write(updated) }
                state = state.copy(favorites = updated, message = UiMessage(R.string.favorites_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = state.copy(message = UiMessage(R.string.favorites_storage_failed))
            } finally {
                state = state.copy(favoritesBusy = false)
            }
        }
    }

    override fun onStop() {
        // Сканирование mDNS держит радио включённым — на время невидимости
        // приложения оно останавливается. Сохранённую связь проверяем при возврате.
        runCatching { discovery?.stop() }
        if (scanJob?.isActive == true) actions.cancelNtpScan()
        super.onStop()
    }

    override fun onDestroy() {
        actionGeneration++
        actionJob?.cancel()
        if (usbReceiverRegistered) unregisterReceiver(usbReceiver)
        scanJob?.cancel()
        runCatching { discovery?.close() }
        // Закрытие тоже идёт по сокету, а lifecycleScope здесь уже отменён:
        // на главном потоке соединение осталось бы полузакрытым
        thread { usb.close(); connector.disconnect() }
        super.onDestroy()
    }

    private suspend fun checkDeviceConnection(returning: Boolean) = deviceOperations.withLock {
        if (state.busy) return@withLock
        val connection = state.connection
        if (connection !is ConnectionState.Connected && connection !is ConnectionState.Checking) return@withLock
        val target = connection.targetOrNull() ?: return@withLock
        val generation = ++actionGeneration
        // Обычная проверка не меняет busy: иначе каждые 10 секунд мигают
        // кнопки и появляется полоса загрузки, сдвигающая поля ввода.
        if (returning) state = state.copy(busy = true, connection = ConnectionState.Checking(target))
        try {
            val result = runInterruptible(Dispatchers.IO) { connector.checkConnection() }
            // Новая команда пока ждёт Mutex. Ей нужен актуальный результат
            // проверки, но отключённое через USB receiver устройство не восстанавливаем.
            if (state.connection.targetOrNull() != target) return@withLock
            state = if (result is ConnectionState.Connected) state.copy(connection = result) else {
                val event = journal.record(Operation.DISCONNECT, Outcome.FAILED, diagnosticTransport(target),
                    reason = if (target is UsbDeviceAddress) ConnectionError.USB_DISCONNECTED else ConnectionError.UNREACHABLE)
                state.connectionLost().copy(message = UiMessage(R.string.connect_connection_lost), diagnosticEventId = event)
            }
        } finally {
            if (returning && generation == actionGeneration) state = state.copy(busy = false)
        }
    }

    private fun refreshUsbList(report: Boolean = false) {
        runCatching { usb.scan() }.fold(onSuccess = { listing ->
            val observation = usb.observation(listing)
            val changed = state.usbSupported != usb.supported || state.usbDevices != listing.devices ||
                state.usbAttachedCount != listing.attachedCount || state.usbScanFailed ||
                state.usbSystemState != observation.system
            state = state.copy(usbSupported = usb.supported, usbDevices = listing.devices,
                usbAttachedCount = listing.attachedCount, usbScanFailed = false, usbSystemState = observation.system)
            if (changed || report) journal.record(Operation.USB_SCAN, Outcome.SUCCESS, DiagnosticTransport.USB,
                usb = observation,
                issue = when {
                    !usb.supported -> DiagnosticIssue.USB_HOST_UNSUPPORTED
                    listing.attachedCount == 0 -> DiagnosticIssue.USB_NONE
                    listing.devices.isEmpty() -> DiagnosticIssue.USB_NO_ADB
                    else -> null
                })
        }, onFailure = { error ->
            val observation = usb.observation(null)
            val event = journal.record(Operation.USB_SCAN, Outcome.FAILED, DiagnosticTransport.USB,
                issue = DiagnosticIssue.USB_ENUMERATION, error = error, usb = observation)
            state = state.copy(usbSupported = usb.supported, usbDevices = emptyList(), usbScanFailed = true,
                usbSystemState = observation.system,
                message = UiMessage(R.string.usb_scan_failed), diagnosticEventId = event)
        })
    }

    private fun missingDiscoveryPermissions(): List<String> =
        discoveryPermissions(Build.VERSION.SDK_INT, applicationInfo.targetSdkVersion).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun networkAllowed(): Boolean {
        if (missingDiscoveryPermissions().isEmpty()) return true
        state = state.copy(discoveryPermissionNeeded = true,
            message = UiMessage(R.string.discovery_permission_needed))
        requestDiscoveryPermissions()
        return false
    }

    private fun requestDiscoveryPermissions() {
        val missing = missingDiscoveryPermissions()
        if (missing.isEmpty()) {
            startDiscovery()
            return
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Создаёт обнаружение при первом обращении и подписывает на него экран.
     *
     * Всё, что делает mDNS, обёрнуто: системный NsdManager и слой поверх него —
     * единственная часть приложения, которая обращается к API, чьё поведение
     * заметно меняется от версии к версии и от прошивки к прошивке. Падение
     * здесь роняло весь процесс на старте, хотя обнаружение — необязательная
     * помощь: адрес всегда можно ввести руками. Причина при этом обязана
     * оказаться на экране: молчаливое исчезновение ошибки в этом проекте уже
     * трижды стоило дороже некрасивого текста.
     */
    private fun startDiscovery() {
        state = state.copy(discoveryPermissionNeeded = false)
        val existing = discovery
        if (existing != null) {
            runCatching { existing.start() }.onFailure { discoveryFailed(it) }
            return
        }
        val created = runCatching { NsdDeviceDiscovery(this) }
            .getOrElse { discoveryFailed(it); return }
        discovery = created
        lifecycleScope.launch {
            try {
                created.state.collect { discovered ->
                    state = state.copy(
                        discoveryAvailable = discovered.available,
                        discoverySearching = discovered.searching,
                        discovered = discovered.devices,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discoveryFailed(e)
            }
        }
        runCatching { created.start() }.onFailure { discoveryFailed(it) }
    }

    /** Обнаружение не работает: экран предлагает ввести адрес и называет причину. */
    private fun discoveryFailed(error: Throwable) {
        val event = journal.record(Operation.DISCOVERY, Outcome.FAILED, DiagnosticTransport.NETWORK, error = error)
        state = state.copy(
            discoveryAvailable = false,
            discoverySearching = false,
            message = UiMessage(R.string.discovery_failed), diagnosticEventId = event,
        )
    }

    /**
     * Дочитывает сведения об устройстве, если соединение живо.
     *
     * Сбой обязан быть виден. Раньше исключение уходило в `getOrNull()`, и
     * раздел «Устройство» оставался пустым, а кнопка «Обновить» выглядела
     * ненажатой — отличить одно от другого было нечем.
     */
    private suspend fun AppState.withDeviceData(trace: OperationTrace): AppState {
        val clean = copy(deviceInfo = null, deviceName = "", ntpMessage = null, ntpCheck = null, ntpDiagnosticEventId = null,
            timeZoneResult = null, timeZoneDiagnosticEventId = null, timeCheck = null, timeDiagnosticEventId = null)
        if (connection !is ConnectionState.Connected) return clean
        return runInterruptible(Dispatchers.IO) {
            val client = connector.activeClient ?: return@runInterruptible clean.copy(
                connection = ConnectionState.Disconnected,
                message = UiMessage(R.string.error_unreachable),
            )
            runCatching { DeviceRepository(trace.client(client)).readDeviceInfo() }.fold(
                onSuccess = { clean.copy(deviceInfo = it, deviceName = it.displayName) },
                onFailure = {
                    if (it is CancellationException) throw it
                    val event = journal.record(Operation.READ_DEVICE, Outcome.FAILED,
                        diagnosticTransport(connection.targetOrNull()), error = it, trace = trace)
                    clean.copy(message = UiMessage(R.string.operation_failed_hint), diagnosticEventId = event)
                },
            )
        }
    }

    private suspend fun AppState.withDeviceTime(trace: OperationTrace, reference: String? = null): AppState = runInterruptible(Dispatchers.IO) {
        val client = connector.activeClient
        val check = if (!connected || client == null) DeviceTimeCheck(DeviceTimeStatus.DEVICE_UNAVAILABLE)
            else timeVerifier.verify(trace.client(client), referenceOverride = reference, onFailure = trace::exception)
        trace.deviceTime(check)
        copy(timeCheck = check)
    }

    private fun TimeZoneUpdateResult.Failed.diagnosticIssue(): DiagnosticIssue =
        if (restoration == TimeZoneRestoration.UNCONFIRMED) DiagnosticIssue.TIME_ZONE_RESTORE_FAILED else when (reason) {
            TimeZoneFailure.INVALID_ZONE -> DiagnosticIssue.INVALID_TIME_ZONE
            TimeZoneFailure.UNSUPPORTED -> DiagnosticIssue.TIME_ZONE_UNSUPPORTED
            TimeZoneFailure.READ_STATE -> DiagnosticIssue.TIME_ZONE_READ_FAILED
            TimeZoneFailure.AUTO_MODE -> DiagnosticIssue.TIME_ZONE_AUTO_FAILED
            TimeZoneFailure.WRITE -> DiagnosticIssue.TIME_ZONE_WRITE_FAILED
        }

    private fun DeviceTimeCheck.diagnosticIssue(): DiagnosticIssue? = when (status) {
        DeviceTimeStatus.MATCH -> null
        DeviceTimeStatus.MISMATCH -> DiagnosticIssue.TIME_MISMATCH
        DeviceTimeStatus.UNCERTAIN -> DiagnosticIssue.TIME_UNCERTAIN
        DeviceTimeStatus.NO_SERVER -> DiagnosticIssue.INVALID_NTP
        DeviceTimeStatus.SYSTEM_DEFAULT -> null
        DeviceTimeStatus.NTP_UNAVAILABLE -> DiagnosticIssue.NTP_UNREACHABLE
        DeviceTimeStatus.DEVICE_UNAVAILABLE -> DiagnosticIssue.TIME_UNAVAILABLE
    }

    private fun diagnosticTransport(target: DeviceTarget?): DiagnosticTransport = when (target) {
        is UsbDeviceAddress -> DiagnosticTransport.USB
        null -> DiagnosticTransport.NONE
        else -> DiagnosticTransport.NETWORK
    }
}
