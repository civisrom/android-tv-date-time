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
import com.civisrom.tvtimefixer.adb.KadbDeviceDiscovery
import com.civisrom.tvtimefixer.device.DeviceRepository
import com.civisrom.tvtimefixer.ui.AppActions
import com.civisrom.tvtimefixer.ui.AppState
import com.civisrom.tvtimefixer.ui.MainScreen
import com.civisrom.tvtimefixer.ui.UiMessage
import com.civisrom.tvtimefixer.ui.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val factory: AdbClientFactory = KadbAdbClientFactory()
    private val connector = DeviceConnector(factory)
    private var discovery: DeviceDiscovery? = null

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

        override fun applyNtpServer(server: String) = run {
            val client = connector.activeClient ?: return@run state
            withContext(Dispatchers.IO) {
                val repository = DeviceRepository(client)
                val result = repository.setNtpServer(server)
                // Значение перечитывается всегда: `settings put` рапортует об
                // успехе и тогда, когда записи не произошло
                state.copy(
                    message = result.toUiMessage(),
                    currentNtpServer = repository.currentNtpServer(),
                )
            }
        }

        override fun refreshDeviceInfo() = run { state.withDeviceData() }

        override fun requestDiscoveryPermission() = requestDiscoveryPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = detectDeviceMode(this)

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
        } else {
            requestDiscoveryPermissions()
        }
    }

    override fun onStop() {
        // Сканирование mDNS держит радио включённым — на время невидимости
        // приложения оно останавливается, но соединение с устройством живёт
        discovery?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        discovery?.close()
        connector.disconnect()
        super.onDestroy()
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

    /** Создаёт обнаружение при первом обращении и подписывает на него экран. */
    private fun startDiscovery() {
        state = state.copy(discoveryPermissionNeeded = false)
        val existing = discovery
        if (existing != null) {
            existing.start()
            return
        }
        val created = KadbDeviceDiscovery(this)
        discovery = created
        lifecycleScope.launch {
            created.state.collect { discovered ->
                state = state.copy(
                    discoveryAvailable = discovered.available,
                    discoverySearching = discovered.searching,
                    discovered = discovered.devices,
                )
            }
        }
        created.start()
    }

    /** Дочитывает сведения об устройстве, если соединение живо. */
    private suspend fun AppState.withDeviceData(): AppState {
        if (connection !is ConnectionState.Connected) return this
        val client = connector.activeClient ?: return this
        return withContext(Dispatchers.IO) {
            val repository = DeviceRepository(client)
            val info = runCatching { repository.readDeviceInfo() }.getOrNull()
            copy(deviceInfo = info, currentNtpServer = info?.currentNtpServer.orEmpty())
        }
    }
}
