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
import com.civisrom.tvtimefixer.data.NtpScanner
import com.civisrom.tvtimefixer.data.ScanProgress
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
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.Outcome
import com.civisrom.tvtimefixer.diagnostics.DiagnosticIssue
import com.civisrom.tvtimefixer.diagnostics.DiagnosticTransport
import com.civisrom.tvtimefixer.adb.DeviceTarget
import com.civisrom.tvtimefixer.device.NtpUpdateResult
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
                    state = state.copy(connection = ConnectionState.Disconnected, busy = true,
                        operation = Operation.DISCONNECT, diagnosticEventId = event,
                        deviceInfo = null, currentNtpServer = "", ntpMessage = null, ntpDiagnosticEventId = null,
                        timeCheck = null, timeDiagnosticEventId = null,
                        message = UiMessage(R.string.error_usb_disconnected))
                    lifecycleScope.launch {
                        // Даже releaseInterface/close могут ждать kernel I/O.
                        // Освобождаем USB вне UI, до следующего подключения.
                        withContext(Dispatchers.IO) {
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
    private val ntpScanner = NtpScanner(ntpProbe)
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

        private fun run(operation: Operation, block: suspend () -> AppState) {
            if (state.busy) return
            val generation = ++actionGeneration
            val started = System.nanoTime()
            val transport = when (operation) {
                Operation.CONNECT_USB -> DiagnosticTransport.USB
                Operation.CONNECT_NETWORK, Operation.PAIR -> DiagnosticTransport.NETWORK
                else -> diagnosticTransport(state.connection.targetOrNull())
            }
            val ntpAction = operation == Operation.CHECK_NTP || operation == Operation.APPLY_NTP
            val timeAction = operation == Operation.CHECK_TIME
            val resetTime = operation in setOf(Operation.CONNECT_NETWORK, Operation.CONNECT_USB,
                Operation.PAIR, Operation.APPLY_NTP, Operation.CHECK_TIME, Operation.READ_DEVICE)
            state = state.copy(busy = true, operation = operation, diagnosticEventId = null,
                timeCheck = if (resetTime) null else state.timeCheck,
                timeDiagnosticEventId = if (resetTime) null else state.timeDiagnosticEventId,
                ntpDiagnosticEventId = if (ntpAction) null else state.ntpDiagnosticEventId)
            journal.record(operation, Outcome.STARTED, transport)
            actionJob = lifecycleScope.launch {
                try {
                    // Нажатие во время фоновой проверки ждёт её завершения,
                    // не теряется и не читает тот же ADB-транспорт одновременно.
                    val result = deviceOperations.withLock { block() }
                    val failure = result.connection as? ConnectionState.Failed
                    val failed = when (operation) {
                        Operation.CONNECT_NETWORK, Operation.CONNECT_USB, Operation.PAIR -> !result.connected
                        Operation.CHECK_NTP -> result.ntpCheck?.isUsable() != true
                        Operation.APPLY_NTP -> result.ntpMessage?.res != R.string.ntp_applied
                        Operation.CHECK_TIME -> result.timeCheck?.status != DeviceTimeStatus.MATCH
                        Operation.READ_DEVICE -> result.diagnosticEventId != null || !result.connected || result.deviceInfo == null
                        else -> false
                    }
                    val existing = if (timeAction) result.timeDiagnosticEventId else if (ntpAction) result.ntpDiagnosticEventId
                        else failure?.diagnosticId ?: result.diagnosticEventId
                    val event = if (failed && existing != null) existing else journal.record(operation,
                        if (failed) Outcome.FAILED else Outcome.SUCCESS, transport,
                        durationMs = (System.nanoTime() - started) / 1_000_000,
                        reason = failure?.reason ?: ConnectionError.UNREACHABLE.takeIf {
                            failed && !result.connected && operation == Operation.READ_DEVICE
                        }, issue = if (!failed) null else when {
                            timeAction -> result.timeCheck?.diagnosticIssue()
                            ntpAction && result.ntpCheck?.reachable == false -> DiagnosticIssue.NTP_UNREACHABLE
                            ntpAction && result.ntpCheck?.isUsable() == false -> DiagnosticIssue.NTP_UNUSABLE
                            result.ntpMessage?.res == R.string.ntp_not_confirmed -> DiagnosticIssue.NTP_NOT_CONFIRMED
                            result.ntpMessage?.res == R.string.ntp_invalid -> DiagnosticIssue.INVALID_NTP
                            else -> null
                        })
                    if (generation == actionGeneration) state = result.copy(
                        diagnosticEventId = if (!ntpAction && !timeAction && failed) event else result.diagnosticEventId,
                        ntpDiagnosticEventId = if (ntpAction && failed) event else result.ntpDiagnosticEventId,
                        timeDiagnosticEventId = if (timeAction && failed) event else result.timeDiagnosticEventId,
                    ).withLatestUsb(state)
                } catch (e: CancellationException) {
                    journal.record(operation, Outcome.CANCELLED, transport)
                    throw e
                } catch (e: Exception) {
                    val event = journal.record(operation, Outcome.FAILED, transport,
                        durationMs = (System.nanoTime() - started) / 1_000_000, error = e)
                    if (generation == actionGeneration) state = if (timeAction) state.copy(
                        timeCheck = DeviceTimeCheck(DeviceTimeStatus.DEVICE_UNAVAILABLE), timeDiagnosticEventId = event,
                    ) else if (ntpAction) state.copy(
                        ntpMessage = UiMessage(R.string.operation_failed_hint), ntpDiagnosticEventId = event,
                    ) else state.copy(message = UiMessage(R.string.operation_failed_hint), diagnosticEventId = event)
                } finally {
                    if (generation == actionGeneration) state = state.copy(busy = false, operation = null)
                }
            }
        }

        override fun connect(address: String) = run(Operation.CONNECT_NETWORK) {
            val result = withContext(Dispatchers.IO) { connector.connect(address) }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun connectLoopback() = run(Operation.CONNECT_NETWORK) {
            val result = withContext(Dispatchers.IO) { connector.connectLoopback() }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun disconnect() = run(Operation.DISCONNECT) {
            withContext(Dispatchers.IO) { connector.disconnect() }
            AppState(
                discovered = state.discovered,
                discoveryAvailable = state.discoveryAvailable,
                discoverySearching = state.discoverySearching,
                discoveryPermissionNeeded = state.discoveryPermissionNeeded,
                usbSupported = state.usbSupported,
                usbDevices = state.usbDevices,
                usbAttachedCount = state.usbAttachedCount,
                usbScanFailed = state.usbScanFailed,
                usbSystemState = state.usbSystemState,
            )
        }

        override fun refreshUsbDevices() = refreshUsbList(report = true)

        override fun connectUsb(address: UsbDeviceAddress) = run(Operation.CONNECT_USB) {
            withContext(Dispatchers.IO) { connector.disconnect() }
            state = state.copy(connection = ConnectionState.Connecting(address),
                deviceInfo = null, currentNtpServer = "", ntpMessage = null,
                ntpCheck = null, ntpRejected = null,
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
            val result = withContext(Dispatchers.IO) { connector.connectUsb(address) }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun pairAndConnect(
            pairingAddress: String,
            code: String,
            connectAddress: String,
        ) = run(Operation.PAIR) {
            // Спаривание и следующее за ним подключение оба ходят в сеть, а
            // connect внутри блокирующий: на главном потоке это NetworkOnMainThread
            val result = withContext(Dispatchers.IO) {
                connector.pairAndConnect(pairingAddress, code, connectAddress)
            }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun checkNtpServer(server: String) = run(Operation.CHECK_NTP) {
            state = state.copy(ntpMessage = null, ntpCheck = null, ntpRejected = null)
            val result = withContext(Dispatchers.IO) { ntpProbe.test(server) }
            state.copy(ntpCheck = result, ntpRejected = server.takeUnless { result.isUsable() })
        }

        /**
         * Применяет адрес, предварительно убедившись, что он отвечает как
         * сервер времени. Десктопная половина ведёт себя так же: адрес, не
         * прошедший проверку, до устройства не доходит.
         */
        override fun applyNtpServer(server: String, force: Boolean) = run(Operation.APPLY_NTP) {
            state = state.copy(ntpMessage = null, ntpRejected = null)
            val applied = withContext(Dispatchers.IO) {
                val check = if (force) null else ntpProbe.test(server)
                if (check != null && !check.isUsable()) {
                    return@withContext state.copy(
                        ntpCheck = check,
                        ntpRejected = server,
                        ntpMessage = UiMessage(
                            R.string.ntp_check_rejected,
                            listOf(check.error ?: getString(R.string.ntp_check_bad_clock)),
                        ),
                    )
                }
                val client = connector.activeClient ?: return@withContext state.copy(
                    connection = ConnectionState.Disconnected,
                    message = UiMessage(R.string.error_unreachable),
                )
                var failureId: Long? = null
                val repository = DeviceRepository(client) { error ->
                    failureId = journal.record(Operation.APPLY_NTP, Outcome.FAILED,
                        diagnosticTransport(state.connection.targetOrNull()), error = error)
                }
                val result = repository.setNtpServer(server)
                // Значение перечитывается всегда: `settings put` рапортует об
                // успехе и тогда, когда записи не произошло
                state.copy(
                    ntpCheck = check,
                    ntpMessage = if (result is NtpUpdateResult.Failed) UiMessage(R.string.operation_failed_hint) else result.toUiMessage(),
                    ntpDiagnosticEventId = failureId,
                    currentNtpServer = if (result is NtpUpdateResult.Applied) result.server else repository.currentNtpServer(),
                )
            }
            if (applied.ntpMessage?.res != R.string.ntp_applied) return@run applied

            // Сохранение настройки уже подтверждено. Ошибка сравнения часов не отменяет этот факт.
            state = applied.copy(operation = Operation.CHECK_TIME)
            val transport = diagnosticTransport(state.connection.targetOrNull())
            val started = SystemClock.elapsedRealtime()
            journal.record(Operation.CHECK_TIME, Outcome.STARTED, transport)
            val verified = try {
                state.withDeviceTime()
            } catch (e: CancellationException) {
                journal.record(Operation.CHECK_TIME, Outcome.CANCELLED, transport)
                throw e
            }
            val failed = verified.timeCheck?.status != DeviceTimeStatus.MATCH
            val event = journal.record(Operation.CHECK_TIME, if (failed) Outcome.FAILED else Outcome.SUCCESS,
                transport, durationMs = SystemClock.elapsedRealtime() - started,
                issue = verified.timeCheck?.diagnosticIssue())
            verified.copy(timeDiagnosticEventId = event.takeIf { failed })
        }

        override fun verifyDeviceTime() = run(Operation.CHECK_TIME) { state.withDeviceTime() }

        override fun scanNtpServers() {
            if (scanJob?.isActive == true) return
            state = state.copy(ntpMessage = null, ntpCheck = null, ntpRejected = null, ntpDiagnosticEventId = null,
                ntpScan = ScanProgress(0, NtpData.allServers.size, emptyList()))
            journal.record(Operation.SCAN_NTP, Outcome.STARTED)
            val started = System.nanoTime()
            scanJob = lifecycleScope.launch {
                try {
                    ntpScanner.scan(NtpData.allServers).collect { progress ->
                        state = state.copy(ntpScan = progress)
                    }
                    journal.record(Operation.SCAN_NTP, Outcome.SUCCESS,
                        durationMs = (System.nanoTime() - started) / 1_000_000)
                } catch (e: CancellationException) {
                    journal.record(Operation.SCAN_NTP, Outcome.CANCELLED)
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
            state = state.copy(ntpScan = state.ntpScan?.let { it.copy(checked = it.total) })
        }

        override fun refreshDeviceInfo() = run(Operation.READ_DEVICE) { state.withDeviceData() }

        override fun requestDiscoveryPermission() = requestDiscoveryPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    MainScreen(mode = mode, state = state, actions = actions, diagnostics = diagnostics,
                        onRefreshDiagnostics = journal::refresh, onClearDiagnostics = journal::clear)
                }
            }
        }
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

    override fun onStart() {
        super.onStart()
        val connected = state.connection as? ConnectionState.Connected
        if (connected != null && !state.busy) {
            state = state.copy(connection = ConnectionState.Checking(connected.address))
        }
        refreshUsbList()
        if (missingDiscoveryPermissions().isEmpty()) {
            startDiscovery()
        } else if (!permissionsRequested) {
            // Один раз за жизнь Activity: после отказа система отвечает
            // отказом молча, и дёргать её при каждом возврате на экран
            // бессмысленно — дальше решает кнопка «Разрешить»
            permissionsRequested = true
            requestDiscoveryPermissions()
        }
    }

    override fun onStop() {
        // Сканирование mDNS держит радио включённым — на время невидимости
        // приложения оно останавливается. Сохранённую связь проверяем при возврате.
        runCatching { discovery?.stop() }
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
            val result = withContext(Dispatchers.IO) { connector.checkConnection() }
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
    private suspend fun AppState.withDeviceData(): AppState {
        val clean = copy(deviceInfo = null, currentNtpServer = "")
        if (connection !is ConnectionState.Connected) return clean
        return withContext(Dispatchers.IO) {
            val client = connector.activeClient ?: return@withContext clean.copy(
                connection = ConnectionState.Disconnected,
                message = UiMessage(R.string.error_unreachable),
            )
            runCatching { DeviceRepository(client).readDeviceInfo() }.fold(
                onSuccess = { clean.copy(deviceInfo = it, currentNtpServer = it.currentNtpServer) },
                onFailure = {
                    val event = journal.record(Operation.READ_DEVICE, Outcome.FAILED,
                        diagnosticTransport(connection.targetOrNull()), error = it)
                    clean.copy(message = UiMessage(R.string.operation_failed_hint), diagnosticEventId = event)
                },
            )
        }
    }

    private suspend fun AppState.withDeviceTime(): AppState = withContext(Dispatchers.IO) {
        val client = connector.activeClient
        val check = if (!connected || client == null) DeviceTimeCheck(DeviceTimeStatus.DEVICE_UNAVAILABLE)
            else timeVerifier.verify(client)
        copy(timeCheck = check)
    }

    private fun DeviceTimeCheck.diagnosticIssue(): DiagnosticIssue? = when (status) {
        DeviceTimeStatus.MATCH -> null
        DeviceTimeStatus.MISMATCH -> DiagnosticIssue.TIME_MISMATCH
        DeviceTimeStatus.UNCERTAIN -> DiagnosticIssue.TIME_UNCERTAIN
        DeviceTimeStatus.NO_SERVER -> DiagnosticIssue.INVALID_NTP
        DeviceTimeStatus.NTP_UNAVAILABLE -> DiagnosticIssue.NTP_UNREACHABLE
        DeviceTimeStatus.DEVICE_UNAVAILABLE -> DiagnosticIssue.TIME_UNAVAILABLE
    }

    private fun diagnosticTransport(target: DeviceTarget?): DiagnosticTransport = when (target) {
        is UsbDeviceAddress -> DiagnosticTransport.USB
        null -> DiagnosticTransport.NONE
        else -> DiagnosticTransport.NETWORK
    }
}
