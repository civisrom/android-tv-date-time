package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.data.NtpData

/** Действия, которые экран запрашивает у владельца состояния. */
interface AppActions {
    fun connect(address: String)
    fun connectLoopback()
    fun disconnect()
    fun pairAndConnect(pairingAddress: String, code: String, connectAddress: String)
    fun applyNtpServer(server: String)
    fun refreshDeviceInfo()
    fun requestDiscoveryPermission()
}

@Composable
fun MainScreen(mode: DeviceMode, state: AppState, actions: AppActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Поля под overscan: у части телевизоров края экрана обрезаны
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(
            when (mode) {
                DeviceMode.TELEVISION -> stringResource(R.string.mode_television)
                DeviceMode.HANDHELD -> stringResource(R.string.mode_handheld)
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        state.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(message.res, *message.args.toTypedArray()),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        ConnectionSection(mode, state, actions)
        HorizontalDivider()
        DiscoverySection(state, actions)

        if (state.connected) {
            HorizontalDivider()
            NtpSection(state, actions)
            HorizontalDivider()
            DeviceInfoSection(state, actions)
        } else {
            HorizontalDivider()
            PairingSection(state, actions)
        }
    }
}

@Composable
private fun ConnectionSection(mode: DeviceMode, state: AppState, actions: AppActions) {
    var address by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (val connection = state.connection) {
                is ConnectionState.Connected ->
                    stringResource(R.string.connect_state_connected, connection.address.toString())
                is ConnectionState.Connecting ->
                    stringResource(R.string.connect_state_connecting, connection.address.toString())
                is ConnectionState.Failed -> stringResource(connection.reason.messageRes())
                ConnectionState.Disconnected -> stringResource(R.string.connect_state_disconnected)
            },
        )

        if (state.connected) {
            Button(onClick = actions::disconnect, enabled = !state.busy) {
                Text(stringResource(R.string.connect_disconnect))
            }
        } else {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.connect_address_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.connect(address) }, enabled = !state.busy) {
                    Text(stringResource(R.string.connect_action))
                }
                if (mode == DeviceMode.TELEVISION) {
                    // Режим телевизора: попытка достучаться до собственного adbd.
                    // Отдельная кнопка, а не автоматика, потому что не всякая
                    // прошивка это принимает и отказ должен быть понятным.
                    TextButton(onClick = actions::connectLoopback, enabled = !state.busy) {
                        Text(stringResource(R.string.connect_try_loopback))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverySection(state: AppState, actions: AppActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
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
        state.discovered.forEach { device ->
            DiscoveredRow(device, enabled = !state.busy, onConnect = actions::connect)
        }
    }
}

@Composable
private fun DiscoveredRow(
    device: DiscoveredDevice,
    enabled: Boolean,
    onConnect: (String) -> Unit,
) {
    val connectable = device.kind != DiscoveredDevice.Kind.AWAITING_PAIRING
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text("${device.address}  ·  ${stringResource(device.kind.labelRes())}")
            if (connectable) {
                Button(
                    onClick = { onConnect(device.address.toString()) },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.connect_action))
                }
            }
        }
    }
}

@Composable
private fun PairingSection(state: AppState, actions: AppActions) {
    var pairingAddress by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var connectAddress by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pairing_hint), style = MaterialTheme.typography.bodySmall)
        // Самая частая причина неудачи: люди подставляют порт спаривания
        // в подключение, потому что оба показаны на одном экране телевизора
        Text(stringResource(R.string.pairing_port_warning), style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = pairingAddress,
            onValueChange = { pairingAddress = it },
            label = { Text(stringResource(R.string.pairing_address_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.pairing_code_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = connectAddress,
            onValueChange = { connectAddress = it },
            label = { Text(stringResource(R.string.pairing_connect_address_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { actions.pairAndConnect(pairingAddress, code, connectAddress) },
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.pairing_action))
        }
    }
}

@Composable
private fun NtpSection(state: AppState, actions: AppActions) {
    var custom by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query) {
        if (query.isBlank()) emptyList() else NtpData.countries.filter { country ->
            query.trim().lowercase().let { needle ->
                country.code.contains(needle) ||
                    country.nameEn.lowercase().contains(needle) ||
                    country.nameRu.lowercase().contains(needle)
            }
        }.take(8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ntp_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.currentNtpServer.isEmpty()) {
                stringResource(R.string.ntp_current_unset)
            } else {
                stringResource(R.string.ntp_current, state.currentNtpServer)
            },
        )

        Text(stringResource(R.string.ntp_by_country), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.ntp_search_country)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        matches.forEach { country ->
            TextButton(
                onClick = { actions.applyNtpServer(country.server) },
                enabled = !state.busy,
            ) {
                Text("${country.code.uppercase()} · ${country.nameEn} · ${country.server}")
            }
        }

        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            label = { Text(stringResource(R.string.ntp_custom_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { actions.applyNtpServer(custom) }, enabled = !state.busy) {
            Text(stringResource(R.string.ntp_apply))
        }
    }
}

@Composable
private fun DeviceInfoSection(state: AppState, actions: AppActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleMedium)
        state.deviceInfo?.let { info ->
            InfoRow(stringResource(R.string.info_model), info.model)
            InfoRow(stringResource(R.string.info_manufacturer), info.manufacturer)
            InfoRow(stringResource(R.string.info_android), info.androidVersion)
            InfoRow(stringResource(R.string.info_api), info.apiLevel)
            InfoRow(stringResource(R.string.info_serial), info.serial)
            InfoRow(stringResource(R.string.info_cpu), info.cpuAbi)
            InfoRow(stringResource(R.string.info_timezone), info.timezone)
            InfoRow(stringResource(R.string.info_locale), info.locale)
            InfoRow(stringResource(R.string.info_battery), info.batteryLevel)
            InfoRow(stringResource(R.string.info_ram), info.totalRam)
            InfoRow(stringResource(R.string.info_screen), info.screenResolution)
            InfoRow(stringResource(R.string.info_uptime), info.uptime)
            InfoRow(stringResource(R.string.info_kernel), info.kernelVersion)
            InfoRow(stringResource(R.string.info_ntp), info.currentNtpServer)
        }
        Button(onClick = actions::refreshDeviceInfo, enabled = !state.busy) {
            Text(stringResource(R.string.info_refresh))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
