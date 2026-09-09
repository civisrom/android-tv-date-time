package com.civisrom.tvtimefixer.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.R

@Composable
internal fun SetupScreen(state: AppState, actions: AppActions, onBack: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val nextFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    BackHandler { if (step > 0) step-- else onBack() }
    LaunchedEffect(step) { scroll.scrollTo(0); nextFocus.requestFocus() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(scroll).padding(horizontal = 48.dp, vertical = 27.dp)
        .testTag("setup-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_step, step + 1, 3))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (step < 2) step++ else onBack() },
                modifier = Modifier.focusRequester(nextFocus).testTag("setup-next")) {
                Text(stringResource(if (step < 2) R.string.setup_next else R.string.setup_finish))
            }
            TextButton(onClick = { if (step > 0) step-- else onBack() }, modifier = Modifier.testTag("setup-back")) {
                Text(stringResource(R.string.setup_back))
            }
        }
        when (step) {
            0 -> {
                Text(stringResource(R.string.setup_intro))
                Text(stringResource(R.string.setup_developer_instructions))
                Text(stringResource(R.string.setup_developer_state, stringResource(flagText(state.localSetup.developerOptions))))
                Button(onClick = { actions.openSetupSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) },
                    modifier = Modifier.testTag("setup-about")) { Text(stringResource(R.string.setup_about)) }
                Button(onClick = { actions.openSetupSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                    modifier = Modifier.testTag("setup-developer")) { Text(stringResource(R.string.setup_developer)) }
            }
            1 -> {
                Text(stringResource(R.string.setup_debug_state, stringResource(flagText(state.localSetup.usbDebugging))))
                Text(stringResource(R.string.setup_debug_state_note))
                Text(stringResource(if (state.localSetup.wirelessGuide) R.string.setup_wireless else R.string.setup_legacy))
                Text(stringResource(R.string.setup_self_pairing))
                Button(onClick = { actions.openSetupSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) {
                    Text(stringResource(R.string.setup_developer))
                }
            }
            else -> {
                Text(stringResource(R.string.setup_connect_note))
                Button(onClick = actions::connectLoopback, enabled = !state.busy, modifier = Modifier.testTag("setup-connect")) {
                    Text(stringResource(R.string.connect_try_loopback))
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when (val connection = state.connection) {
                    is com.civisrom.tvtimefixer.adb.ConnectionState.Connected -> stringResource(R.string.connect_state_connected, connection.address.toString())
                    is com.civisrom.tvtimefixer.adb.ConnectionState.Failed -> stringResource(connection.reason.messageRes())
                    else -> stringResource(R.string.setup_return_to_form)
                })
                state.message?.let { Text(stringResource(it.res, *it.args.toTypedArray())) }
                Button(onClick = { actions.openSetupSettings(Settings.ACTION_DATE_SETTINGS) }) {
                    Text(stringResource(R.string.setup_date_settings))
                }
            }
        }
    }
}

private fun flagText(value: Boolean?): Int = when (value) {
    true -> R.string.setup_enabled
    false -> R.string.setup_disabled
    null -> R.string.setup_unknown
}
