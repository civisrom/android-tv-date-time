package com.civisrom.tvtimefixer

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.civisrom.tvtimefixer.adb.AdbClientFactory
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DeviceConnector
import com.civisrom.tvtimefixer.adb.DeviceDiscovery
import com.civisrom.tvtimefixer.adb.KadbAdbClientFactory
import com.civisrom.tvtimefixer.adb.NsdDeviceDiscovery
import com.civisrom.tvtimefixer.data.NtpData
import com.civisrom.tvtimefixer.data.NtpProbe
import com.civisrom.tvtimefixer.data.NtpScanner
import com.civisrom.tvtimefixer.data.isUsable
import com.civisrom.tvtimefixer.device.DeviceRepository
import com.civisrom.tvtimefixer.net.UdpSntpClient
import com.civisrom.tvtimefixer.ui.AppActions
import com.civisrom.tvtimefixer.ui.AppState
import com.civisrom.tvtimefixer.ui.MainScreen
import com.civisrom.tvtimefixer.ui.UiMessage
import com.civisrom.tvtimefixer.ui.toUiMessage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val factory: AdbClientFactory = KadbAdbClientFactory()
    private val connector = DeviceConnector(factory)
    private var discovery: DeviceDiscovery? = null
    private var permissionsRequested = false

    private val ntpProbe = NtpProbe(UdpSntpClient())
    private val ntpScanner = NtpScanner(ntpProbe)
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
            // Отказ не ломает приложение: адрес всегда можно ввести руками
            state = state.copy(discoveryPermissionNeeded = true)
        }
    }

    private val actions = object : AppActions {

        private fun run(block: suspend () -> AppState) {
            state = state.copy(busy = true)
            lifecycleScope.launch {
                state = try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Молчаливое исчезновение ошибки хуже некрасивого текста:
                    // человек иначе не поймёт, почему ничего не произошло
                    state.copy(
                        message = UiMessage(
                            R.string.action_failed,
                            listOf(e.message ?: e.javaClass.simpleName),
                        ),
                    )
                }.copy(busy = false)
            }
        }

        override fun connect(address: String) = run {
            val result = withContext(Dispatchers.IO) { connector.connect(address) }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun connectLoopback() = run {
            val result = withContext(Dispatchers.IO) { connector.connectLoopback() }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun disconnect() = run {
            withContext(Dispatchers.IO) { connector.disconnect() }
            AppState(
                discovered = state.discovered,
                discoveryAvailable = state.discoveryAvailable,
                discoverySearching = state.discoverySearching,
                discoveryPermissionNeeded = state.discoveryPermissionNeeded,
            )
        }

        override fun pairAndConnect(
            pairingAddress: String,
            code: String,
            connectAddress: String,
        ) = run {
            // Спаривание и следующее за ним подключение оба ходят в сеть, а
            // connect внутри блокирующий: на главном потоке это NetworkOnMainThread
            val result = withContext(Dispatchers.IO) {
                connector.pairAndConnect(pairingAddress, code, connectAddress)
            }
            state.copy(connection = result, message = null).withDeviceData()
        }

        override fun checkNtpServer(server: String) = run {
            state = state.copy(ntpMessage = null, ntpCheck = null, ntpRejected = null)
            val result = withContext(Dispatchers.IO) { ntpProbe.test(server) }
            state.copy(ntpCheck = result, ntpRejected = server.takeUnless { result.isUsable() })
        }

        /**
         * Применяет адрес, предварительно убедившись, что он отвечает как
         * сервер времени. Десктопная половина ведёт себя так же: адрес, не
         * прошедший проверку, до устройства не доходит.
         */
        override fun applyNtpServer(server: String, force: Boolean) = run {
            state = state.copy(ntpMessage = null, ntpRejected = null)
            withContext(Dispatchers.IO) {
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
                val repository = DeviceRepository(client)
                val result = repository.setNtpServer(server)
                // Значение перечитывается всегда: `settings put` рапортует об
                // успехе и тогда, когда записи не произошло
                state.copy(
                    ntpCheck = check,
                    ntpMessage = result.toUiMessage(),
                    currentNtpServer = repository.currentNtpServer(),
                )
            }
        }

        override fun scanNtpServers() {
            if (scanJob?.isActive == true) return
            state = state.copy(ntpMessage = null, ntpCheck = null, ntpRejected = null)
            scanJob = lifecycleScope.launch {
                try {
                    ntpScanner.scan(NtpData.allServers).collect { progress ->
                        state = state.copy(ntpScan = progress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = state.copy(
                        ntpScan = null,
                        ntpMessage = UiMessage(R.string.action_failed, listOf(reasonOf(e))),
                    )
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

        override fun refreshDeviceInfo() = run { state.withDeviceData() }

        override fun requestDiscoveryPermission() = requestDiscoveryPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordUncaughtExceptions()
        val mode = detectDeviceMode(this)
        lastCrash()?.let { state = state.copy(message = UiMessage(R.string.last_crash, listOf(it))) }

        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(mode = mode, state = state, actions = actions)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
        // приложения оно останавливается, но соединение с устройством живёт
        runCatching { discovery?.stop() }
        super.onStop()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        runCatching { discovery?.close() }
        // Закрытие тоже идёт по сокету, а lifecycleScope здесь уже отменён:
        // на главном потоке соединение осталось бы полузакрытым
        thread { connector.disconnect() }
        super.onDestroy()
    }

    /**
     * Сохраняет трассировку падения, чтобы показать её при следующем запуске.
     *
     * Обнаружение по mDNS отвечает колбэками NsdManager, которые приходят на
     * главный поток уже после возврата из start(): исключение оттуда не ловится
     * никаким try/catch вокруг вызова и убивает процесс молча, не оставляя
     * пользователю ни окна, ни следа. Единственное место, где такое ещё можно
     * перехватить, — обработчик по умолчанию.
     */
    private fun recordUncaughtExceptions() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                PrintWriter(writer).use { error.printStackTrace(it) }
                File(filesDir, CRASH_FILE).writeText(thread.name + "\n" + writer)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Трассировка прошлого падения, если она есть. Читается один раз. */
    private fun lastCrash(): String? {
        val file = File(filesDir, CRASH_FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.take(1200)
    }

    private fun missingDiscoveryPermissions(): List<String> =
        discoveryPermissions(Build.VERSION.SDK_INT).filter {
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
        state = state.copy(
            discoveryAvailable = false,
            discoverySearching = false,
            message = UiMessage(R.string.discovery_failed, listOf(reasonOf(error))),
        )
    }

    private companion object {
        const val CRASH_FILE = "last-crash.txt"
    }

    /**
     * Дочитывает сведения об устройстве, если соединение живо.
     *
     * Сбой обязан быть виден. Раньше исключение уходило в `getOrNull()`, и
     * раздел «Устройство» оставался пустым, а кнопка «Обновить» выглядела
     * ненажатой — отличить одно от другого было нечем.
     */
    private suspend fun AppState.withDeviceData(): AppState {
        if (connection !is ConnectionState.Connected) return this
        return withContext(Dispatchers.IO) {
            val client = connector.activeClient ?: return@withContext copy(
                connection = ConnectionState.Disconnected,
                message = UiMessage(R.string.error_unreachable),
            )
            runCatching { DeviceRepository(client).readDeviceInfo() }.fold(
                onSuccess = { copy(deviceInfo = it, currentNtpServer = it.currentNtpServer) },
                onFailure = { copy(message = UiMessage(R.string.action_failed, listOf(reasonOf(it)))) },
            )
        }
    }

    /** Причина сбоя в виде, пригодном для показа человеку. */
    private fun reasonOf(error: Throwable): String =
        error.javaClass.simpleName + ": " + (error.message ?: "")
}
