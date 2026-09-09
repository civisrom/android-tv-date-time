package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
    if (state.connectedAddress != null) Button(shape = MaterialTheme.shapes.medium, onClick = { creating = true }, enabled = enabled && state.deviceInfo != null,
        modifier = Modifier.testTag("favorite-device-save")) { Text(stringResource(R.string.favorite_current_device)) }
    if (state.favorites.devices.isEmpty()) Text(stringResource(R.string.favorites_empty))
    state.favorites.devices.forEach { device ->
        Text(device.name, style = MaterialTheme.typography.titleSmall)
        Text("${device.model} · ${device.address}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(shape = MaterialTheme.shapes.medium, onClick = { actions.connectFavorite(device) }, enabled = enabled,
                modifier = Modifier.testTag("favorite-connect-${device.serial}")) { Text(stringResource(R.string.connect_action)) }
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { editing = device }, enabled = enabled) { Text(stringResource(R.string.favorites_edit)) }
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { deleting = device }, enabled = enabled) { Text(stringResource(R.string.favorites_delete)) }
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
                Button(shape = MaterialTheme.shapes.medium, onClick = { actions.removeFavoriteDevice(device.serial); deleting = null }) {
                    Text(stringResource(R.string.favorites_delete))
                }
            }, dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = { deleting = null }) { Text(stringResource(R.string.diagnostics_cancel)) } })
    }
}

@Composable
internal fun NtpFavorites(state: AppState, actions: AppActions, server: String, onPick: (String) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    val enabled = state.favoritesReady && !state.favoritesBusy
    if (isValidNtpServer(server)) TextButton(shape = MaterialTheme.shapes.medium, onClick = { editing = server.trim() }, enabled = enabled,
        modifier = Modifier.testTag("favorite-ntp-save")) { Text(stringResource(R.string.favorite_ntp_save)) }
    state.favorites.servers.forEach { item ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(shape = MaterialTheme.shapes.medium, onClick = { onPick(item.server) }, modifier = Modifier.testTag("favorite-ntp-${item.server}")) {
                Text(if (item.name.equals(item.server, ignoreCase = true)) item.server else "${item.name} · ${item.server}")
            }
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { actions.removeFavoriteNtp(item.server) }, enabled = enabled) {
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
    val saveFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = name.isNotBlank() && name.none { it.isISOControl() } &&
        (initialAddress == null || parseDeviceAddress(address) != null)
    val finishInput = KeyboardActions(onDone = { if (canSave) saveFocus.requestFocus(); keyboard?.hide() })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.favorites_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.favorite_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true, modifier = Modifier.testTag("favorite-name"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = finishInput)
            if (initialAddress != null) {
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.connect_address_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, modifier = Modifier.testTag("favorite-address"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = finishInput)
                Text(stringResource(R.string.favorite_address_hint))
            }
        }
    }, confirmButton = {
        Button(shape = MaterialTheme.shapes.medium, onClick = { onSave(name.trim(), address.trim()) },
            enabled = canSave, modifier = Modifier.focusRequester(saveFocus).focusProperties { canFocus = true }
                .testTag("favorite-confirm")) {
            Text(stringResource(R.string.favorites_save))
        }
    }, dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = onDismiss) { Text(stringResource(R.string.diagnostics_cancel)) } })
}

/** На TV системное touch-меню буфера бывает недоступно. Эти кнопки доступны пульту. */
@Suppress("DEPRECATION")
@Composable
internal fun AddressCopy(text: String, tag: String) {
    if (detectDeviceMode(LocalContext.current) != DeviceMode.TELEVISION) return
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    TextButton(shape = MaterialTheme.shapes.medium, onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, modifier = Modifier.testTag("copy-$tag")) {
        Text(stringResource(if (copied) R.string.address_copied else R.string.address_copy))
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun AddressPaste(tag: String, onPaste: (String) -> Unit) {
    if (detectDeviceMode(LocalContext.current) != DeviceMode.TELEVISION) return
    val clipboard = LocalClipboardManager.current
    TextButton(shape = MaterialTheme.shapes.medium, onClick = { clipboard.getText()?.text?.take(253)?.let(onPaste) }, modifier = Modifier.testTag("paste-$tag")) {
        Text(stringResource(R.string.address_paste))
    }
}
