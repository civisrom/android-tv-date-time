package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.detectDeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.data.*

@Composable
internal fun DeviceFavorites(state: AppState, actions: AppActions) {
    var editing by remember { mutableStateOf<FavoriteDevice?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FavoriteDevice?>(null) }
    LaunchedEffect(state.connection) { creating = false }
    val enabled = state.favoritesReady && !state.favoritesBusy && !state.busy
    Text(stringResource(R.string.favorite_device_hint))
    if (state.connectedAddress != null) Button(onClick = { creating = true }, enabled = enabled && state.deviceInfo != null,
        modifier = Modifier.testTag("favorite-device-save")) { Text(stringResource(R.string.favorite_current_device)) }
    if (state.favorites.devices.isEmpty()) Text(stringResource(R.string.favorites_empty))
    state.favorites.devices.forEach { device ->
        Text(device.name, style = MaterialTheme.typography.titleSmall)
        Text("${device.model} · ${device.address}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.connectFavorite(device) }, enabled = enabled,
                modifier = Modifier.testTag("favorite-connect-${device.serial}")) { Text(stringResource(R.string.connect_action)) }
            TextButton(onClick = { editing = device }, enabled = enabled) { Text(stringResource(R.string.favorites_edit)) }
            TextButton(onClick = { deleting = device }, enabled = enabled) { Text(stringResource(R.string.favorites_delete)) }
        }
    }
    if (creating || editing != null) {
        val selected = editing
        FavoriteEditor(selected?.name ?: state.deviceInfo?.model.orEmpty(), selected?.address?.toString(),
            onDismiss = { creating = false; editing = null }, onSave = { name, address ->
                if (selected == null) actions.saveCurrentDevice(name)
                else actions.updateFavoriteDevice(selected.copy(name = name, address = requireNotNull(parseDeviceAddress(address))))
                creating = false; editing = null
            })
    }
    deleting?.let { device ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.favorites_delete)) },
            text = { Text(device.name) }, confirmButton = {
                Button(onClick = { actions.removeFavoriteDevice(device.serial); deleting = null }) {
                    Text(stringResource(R.string.favorites_delete))
                }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.diagnostics_cancel)) } })
    }
}

@Composable
internal fun NtpFavorites(state: AppState, actions: AppActions, server: String, onPick: (String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    val enabled = state.favoritesReady && !state.favoritesBusy
    if (isValidNtpServer(server)) TextButton(onClick = { editing = server.trim() }, enabled = enabled,
        modifier = Modifier.testTag("favorite-ntp-save")) { Text(stringResource(R.string.favorite_ntp_save)) }
    state.favorites.servers.forEach { item ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onPick(item.server) }, modifier = Modifier.testTag("favorite-ntp-${item.server}")) {
                Text("${item.name} · ${item.server}")
            }
            TextButton(onClick = { actions.removeFavoriteNtp(item.server) }, enabled = enabled) {
                Text(stringResource(R.string.favorites_delete))
            }
        }
    }
    editing?.let { chosen ->
        FavoriteEditor(state.favorites.servers.firstOrNull { it.server == chosen }?.name ?: chosen, null,
            onDismiss = { editing = null }, onSave = { name, _ -> actions.saveFavoriteNtp(name, chosen); editing = null })
    }
}

@Composable
private fun FavoriteEditor(initialName: String, initialAddress: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName.take(80)) }
    var address by remember { mutableStateOf(initialAddress.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.favorites_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.favorite_name)) },
                singleLine = true, modifier = Modifier.testTag("favorite-name"))
            if (initialAddress != null) {
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.connect_address_hint)) },
                    singleLine = true, modifier = Modifier.testTag("favorite-address"))
                Text(stringResource(R.string.favorite_address_hint))
            }
        }
    }, confirmButton = {
        Button(onClick = { onSave(name.trim(), address.trim()) },
            enabled = name.isNotBlank() && name.none { it.isISOControl() } &&
                (initialAddress == null || parseDeviceAddress(address) != null), modifier = Modifier.testTag("favorite-confirm")) {
            Text(stringResource(R.string.favorites_save))
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.diagnostics_cancel)) } })
}

/** На TV системное touch-меню буфера бывает недоступно. Эти кнопки доступны пульту. */
@Suppress("DEPRECATION")
@Composable
internal fun AddressCopy(text: String, tag: String) {
    if (detectDeviceMode(LocalContext.current) != DeviceMode.TELEVISION) return
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, modifier = Modifier.testTag("copy-$tag")) {
        Text(stringResource(if (copied) R.string.address_copied else R.string.address_copy))
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun AddressPaste(tag: String, onPaste: (String) -> Unit) {
    if (detectDeviceMode(LocalContext.current) != DeviceMode.TELEVISION) return
    val clipboard = LocalClipboardManager.current
    TextButton(onClick = { clipboard.getText()?.text?.take(253)?.let(onPaste) }, modifier = Modifier.testTag("paste-$tag")) {
        Text(stringResource(R.string.address_paste))
    }
}
