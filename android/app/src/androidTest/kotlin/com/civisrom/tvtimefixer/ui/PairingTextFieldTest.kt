package com.civisrom.tvtimefixer.ui

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PairingTextFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = TextFieldState("one")
    private val second = TextFieldState("two")
    private var submitted = 0
    private var done = 0
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun screen(mode: DeviceMode, firstKeyboard: KeyboardType = KeyboardType.Uri) {
        compose.setContent {
            val keyboard = LocalSoftwareKeyboardController.current
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    PairingTextField(mode, first, R.string.pairing_address_hint, firstKeyboard,
                        modifier = Modifier.testTag("first"), onDone = { done++; keyboard?.hide() })
                    PairingTextField(mode, second, R.string.pairing_connect_address_hint, KeyboardType.Uri,
                        modifier = Modifier.testTag("second"), onDone = { done++; keyboard?.hide() })
                    Button(onClick = { submitted++ }, modifier = Modifier.testTag("submit")) { Text("Submit") }
                }
            }
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() } }
    }

    private fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)

    private fun ime(shown: Boolean) {
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        try {
            var stableSince = SystemClock.uptimeMillis()
            compose.waitUntil(if (shown) 15_000 else 5_000) {
                val matches = if (Build.VERSION.SDK_INT >= 30) {
                    compose.runOnUiThread {
                        compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == shown
                    }
                } else hasLegacyImeWindow(automation) == shown
                val now = SystemClock.uptimeMillis()
                if (!matches) stableSince = now
                matches && now - stableSince >= 500
            }
            automation.waitForIdle(300, 3_000)
        } finally { automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
    }

    private fun initialFocus() {
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("first").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("first").assertIsFocused()
        ime(false)
    }

    @Test fun TV_native_OK_Enter_editing_and_hardware_letters_keep_browsing_available() {
        screen(DeviceMode.TELEVISION)
        initialFocus()
        key(KeyEvent.KEYCODE_A)
        compose.waitUntil(5_000) { first.text.toString() == "onea" }
        ime(false)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ime(true)
        // Stock TV IMEs own remote navigation. A phone IME can forward hardware arrows
        // to the editor even when this test renders a synthetic TV layout.
        if (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("first").assertIsFocused()
        }
        compose.onNodeWithTag("first").performTextInput("7")
        compose.runOnIdle { assertEquals("onea7", first.text.toString()) }
        compose.onNodeWithTag("first").performImeAction()
        ime(false)
        compose.runOnIdle { assertEquals(1, done) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        ime(true)
        key(KeyEvent.KEYCODE_BACK)
        ime(false)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("second").assertIsFocused()
        ime(false)
        key(KeyEvent.KEYCODE_ENTER)
        ime(true)
        compose.onNodeWithTag("second").performTextInput("9")
        compose.runOnIdle { assertEquals("two9", second.text.toString()) }
        key(KeyEvent.KEYCODE_BACK)
        ime(false)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("submit").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun TV_touch_then_hardware_Enter_uses_Done_instead_of_reentering_edit_mode() {
        screen(DeviceMode.TELEVISION)
        initialFocus()
        compose.onNodeWithTag("first").performClick()
        ime(true)
        compose.onNodeWithTag("first").performTextInput("x")
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5_000) { done == 1 }
        ime(false)
        compose.runOnIdle { assertEquals("onex", first.text.toString()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("second").assertIsFocused()
        ime(false)
    }

    @Test fun TV_clearing_an_active_code_keeps_native_input_and_discards_undo() {
        first.setTextAndPlaceCursorAtEnd("246810")
        screen(DeviceMode.TELEVISION, KeyboardType.Number)
        compose.onNodeWithTag("first").performClick()
        ime(true)
        compose.runOnIdle { clearPairingCode(first) }
        compose.runOnIdle { assertTrue("The code must be cleared", first.text.isEmpty()) }
        ime(true)
        key(KeyEvent.KEYCODE_7)
        compose.waitUntil(5_000) { first.text.toString() == "7" }
        val keyTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
        instrumentation.sendKeySync(KeyEvent(keyTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
        compose.waitUntil(5_000) { first.text.isEmpty() }
        key(KeyEvent.KEYCODE_8)
        compose.waitUntil(5_000) { first.text.toString() == "8" }
        compose.onNodeWithTag("first").performImeAction()
        ime(false)
    }

    private fun selectionAndExternalText(mode: DeviceMode) {
        screen(mode)
        val field = compose.onNodeWithTag("first")
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 2, false)) }
        field.performTextInput("X")
        field.performTextInput("Y")
        compose.runOnIdle { assertEquals("oXYe", first.text.toString()) }
        field.performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 1, false)) }
        field.assertTextContains("oXYe")
        field.performTextInput("Z")
        compose.runOnIdle { assertEquals("oZXYe", first.text.toString()) }
        compose.runOnIdle { first.setTextAndPlaceCursorAtEnd("192.0.2.1:12345") }
        field.assertTextContains("192.0.2.1:12345")
        compose.runOnIdle { first.setTextAndPlaceCursorAtEnd("") }
        field.assertTextContains("")
        field.performTextInput("a")
        field.performTextInput("b")
        compose.runOnIdle { assertEquals("ab", first.text.toString()) }
        val selection = field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(2), selection)
    }

    @Test fun TV_selection_rapid_input_external_prefill_and_clear_preserve_text() = selectionAndExternalText(DeviceMode.TELEVISION)

    @Test fun phone_String_editor_keeps_cursor_and_external_paste_behavior() = selectionAndExternalText(DeviceMode.HANDHELD)
}
