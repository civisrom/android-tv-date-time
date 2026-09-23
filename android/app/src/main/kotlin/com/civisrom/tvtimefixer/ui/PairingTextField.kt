package com.civisrom.tvtimefixer.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.civisrom.tvtimefixer.DeviceMode

/** Keep the active input connection, but never let Undo recover a cleared pairing code. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun clearPairingCode(value: TextFieldState) {
    value.clearText()
    value.undoState.clearHistory()
}

/** TV focus browses consecutive fields; OK/Enter or a tap starts input explicitly. */
@Composable
internal fun PairingTextField(
    mode: DeviceMode,
    value: TextFieldState,
    label: Int,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val options = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done)
    if (mode != DeviceMode.TELEVISION) {
        OutlinedTextField(value = value.text.toString(), onValueChange = { value.setTextAndPlaceCursorAtEnd(it) },
            label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true, modifier = modifier, keyboardOptions = options,
            keyboardActions = KeyboardActions(onDone = { onDone() }))
        return
    }

    var editing by remember(value) { mutableStateOf(false) }
    val interaction = remember(value) { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        // A real tap is also an explicit entry, including a tap on an already focused field.
        interaction.interactions.collect { if (it is PressInteraction.Release) editing = true }
    }
    OutlinedTextField(state = value,
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = options.copy(showKeyboardOnFocus = editing),
        onKeyboardAction = { onDone() }, interactionSource = interaction,
        modifier = modifier.onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent {
                if (!editing && it.type == KeyEventType.KeyDown &&
                    !it.isCtrlPressed && !it.isShiftPressed && !it.isAltPressed &&
                    (it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    editing = true
                    true
                } else false
            }.then(tvTextFieldNavigation(mode)))
}
