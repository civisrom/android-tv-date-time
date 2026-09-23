package com.civisrom.tvtimefixer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.civisrom.tvtimefixer.DeviceMode

/** TV remotes leave editors with Up/Down; phones keep normal cursor navigation. */
@Composable
internal fun tvTextFieldNavigation(mode: DeviceMode): Modifier {
    val focus = LocalFocusManager.current
    return if (mode != DeviceMode.TELEVISION) Modifier else Modifier.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown || it.isCtrlPressed || it.isShiftPressed || it.isAltPressed) false
        else when (it.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
