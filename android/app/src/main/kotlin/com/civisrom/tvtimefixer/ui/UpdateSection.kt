package com.civisrom.tvtimefixer.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.update.UpdateActions
import com.civisrom.tvtimefixer.update.UpdateError
import com.civisrom.tvtimefixer.update.UpdatePhase
import com.civisrom.tvtimefixer.update.UpdateState

@StringRes
fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.RATE_LIMITED -> R.string.update_error_rate_limited
    UpdateError.NOT_FOUND -> R.string.update_error_not_found
    UpdateError.BAD_RESPONSE -> R.string.update_error_bad_response
    UpdateError.NO_APK -> R.string.update_error_no_apk
    UpdateError.CHECKSUM -> R.string.update_error_checksum
    UpdateError.STORAGE -> R.string.update_error_storage
    UpdateError.PACKAGE_MISMATCH -> R.string.update_error_package
    UpdateError.NOT_NEWER -> R.string.update_error_not_newer
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
    UpdateError.INSTALL_ABORTED -> R.string.update_error_install_aborted
    UpdateError.INSTALL_BLOCKED -> R.string.update_error_install_blocked
    UpdateError.INSTALL_CONFLICT -> R.string.update_error_install_conflict
    UpdateError.INSTALL_STORAGE -> R.string.update_error_install_storage
    UpdateError.INSTALL_INVALID -> R.string.update_error_install_invalid
    UpdateError.INSTALL_FAILED -> R.string.update_error_install_failed
}

/** Проверка, загрузка и установка новой версии из GitHub Releases. */
@Composable
internal fun UpdateSection(state: UpdateState, actions: UpdateActions, installedVersion: String) {
    val context = LocalContext.current
    val release = state.release
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(Modifier.fillMaxWidth().testTag("update-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_installed_version, installedVersion), style = MaterialTheme.typography.bodyMedium)
        when (state.phase) {
            UpdatePhase.IDLE -> Unit
            UpdatePhase.CHECKING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_checking))
            }
            UpdatePhase.UP_TO_DATE -> Text(stringResource(R.string.update_up_to_date, state.latestVersion))
            UpdatePhase.AVAILABLE -> if (release != null) {
                Text(stringResource(R.string.update_available, release.version.toString(), size(release.apkSize)))
            }
            UpdatePhase.DOWNLOADING -> if (release != null) {
                LinearProgressIndicator(progress = { state.downloaded.toFloat() / release.apkSize }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading, size(state.downloaded), size(release.apkSize)))
            }
            UpdatePhase.PERMISSION_REQUIRED -> {
                Text(stringResource(R.string.update_permission_required))
                if (state.settingsUnavailable) Text(stringResource(R.string.update_settings_unavailable))
            }
            UpdatePhase.INSTALLING -> Text(stringResource(R.string.update_installing))
            UpdatePhase.FAILED -> Text(stringResource(state.error?.messageRes() ?: R.string.update_error_install_failed),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("update-error"))
        }
        if (state.apkReady && release != null) Text(stringResource(R.string.update_ready, release.version.toString()))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.phase == UpdatePhase.DOWNLOADING -> FilledTonalButton(onClick = actions::cancelDownload,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("update-cancel")) {
                    Text(stringResource(R.string.update_cancel))
                }
                state.phase == UpdatePhase.PERMISSION_REQUIRED -> {
                    Button(onClick = actions::openInstallSettings, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag("update-settings")) {
                        Text(stringResource(R.string.update_open_settings))
                    }
                    FilledTonalButton(onClick = actions::install, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag("update-install")) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                state.apkReady -> Button(onClick = actions::install, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.testTag("update-install")) {
                    Text(stringResource(R.string.update_install))
                }
                release != null && !state.busy -> Button(onClick = actions::downloadAndInstall,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("update-download")) {
                    Text(stringResource(R.string.update_download_install))
                }
            }
            if (!state.busy) FilledTonalButton(onClick = actions::check, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.testTag("update-check")) {
                Text(stringResource(R.string.update_check))
            }
        }
    }
}
