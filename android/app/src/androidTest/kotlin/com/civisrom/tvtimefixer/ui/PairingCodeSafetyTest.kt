package com.civisrom.tvtimefixer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.data.DeviceAddress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real MainScreen ownership: an in-memory code must not become saved state or undo history. */
class PairingCodeSafetyTest {
    @get:Rule val compose = createComposeRule()
    private val actions = ScreenActions()
    private val pairingAddress = "192.0.2.10:37123"
    private val connectAddress = "192.0.2.10:40555"
    private val syntheticCode = "246810"

    private fun enterDraft() {
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-address").performScrollTo().performTextInput(pairingAddress)
        compose.onNodeWithTag("pairing-connect-address").performScrollTo().performTextInput(connectAddress)
        compose.onNodeWithTag("pairing-code").performScrollTo().performTextInput(syntheticCode)
        assertCodePresent()
    }

    private fun text(tag: String): String = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun assertCodePresent() {
        // Do not include the code itself in assertion messages or diagnostic output.
        assertTrue("The in-memory draft code was lost", text("pairing-code") == syntheticCode)
    }

    private fun assertCodeEmpty() {
        assertTrue("The old pairing code must remain cleared", text("pairing-code").isEmpty())
    }

    private fun assertAddressesPreserved() {
        assertTrue("The pairing endpoint draft was lost", text("pairing-address") == pairingAddress)
        assertTrue("The connection endpoint draft was lost", text("pairing-connect-address") == connectAddress)
    }

    private fun undoCode(redo: Boolean = false) {
        val code = compose.onNodeWithTag("pairing-code").performScrollTo()
        code.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        code.performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                if (redo) withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } else pressKey(Key.Z)
            }
        }
    }

    @Test fun TV_pairing_draft_survives_diagnostics_without_showing_the_code_there() {
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.TELEVISION, AppState(), actions) } }
        enterDraft()
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onAllNodes(hasText(syntheticCode, substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("pairing-code").performScrollTo()
        assertCodePresent()
        assertAddressesPreserved()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun TV_saved_state_restores_addresses_but_never_the_pairing_code() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { MainScreen(DeviceMode.TELEVISION, AppState(), actions) } }
        enterDraft()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pairing-code").performScrollTo()
        assertCodeEmpty()
        assertAddressesPreserved()
        undoCode()
        assertCodeEmpty()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun TV_connection_discards_code_and_undo_history_but_preserves_addresses() {
        val state = mutableStateOf(AppState())
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.TELEVISION, state.value, actions) } }
        enterDraft()
        // A positive control prevents a broken keyboard/undo path from producing a false PASS.
        undoCode()
        assertCodeEmpty()
        undoCode(redo = true)
        assertCodePresent()

        compose.runOnIdle {
            state.value = state.value.copy(connection = ConnectionState.Connected(DeviceAddress("192.0.2.10", 40555)))
        }
        compose.waitUntil(5_000) { text("pairing-code").isEmpty() }
        assertAddressesPreserved()
        undoCode()
        assertCodeEmpty()
        undoCode(redo = true)
        assertCodeEmpty()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionState.Disconnected) }
        undoCode()
        assertCodeEmpty()
        assertAddressesPreserved()
        assertTrue(actions.calls.isEmpty())
    }
}
