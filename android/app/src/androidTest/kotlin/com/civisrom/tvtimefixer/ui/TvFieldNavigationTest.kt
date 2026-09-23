package com.civisrom.tvtimefixer.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.device.DeviceInfo
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Enter each editor, then use only D-pad traversal to reach its action. */
class TvFieldNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = ScreenActions()
    private lateinit var inputMode: InputModeManager
    private var keyboard: SoftwareKeyboardController? = null
    private val connected = AppState(
        connection = ConnectionState.Connected(DeviceAddress("127.0.0.1", 5555)),
        deviceInfo = DeviceInfo(apiLevel = "36", timezone = "GMT", currentNtpServer = "null"),
    )

    private fun screen(state: AppState = connected) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            keyboard = LocalSoftwareKeyboardController.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(640.dp, 360.dp)) {
                        MainScreen(DeviceMode.TELEVISION, state, actions)
                    }
                }
            }
        }
    }

    private fun enter(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick().performTextReplacement(value)
    }

    private fun traverse(from: String, target: SemanticsMatcher, activate: Boolean = true): Boolean {
        val field = compose.onNodeWithTag(from)
        compose.runOnIdle { keyboard?.hide(); inputMode.requestInputMode(InputMode.Keyboard) }
        // Establish only the starting editor directly; all subsequent navigation uses D-pad.
        // Scrolling before a click races the previous editor's IME bring-into-view request.
        field.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field.assertIsFocused()
        compose.runOnIdle { keyboard?.hide() }
        field.performScrollTo().assertIsDisplayed()
        val route = mutableListOf<String>()
        for (step in 0 until 16) {
            val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString {
                it.config.getOrNull(SemanticsProperties.TestTag) ?: "node-${it.id}"
            }
            route += focused
            if (compose.onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()) {
                println("TV field $from reached action; route=$route")
                val action = compose.onNode(target and isFocused()).assertIsDisplayed()
                if (activate) action.performKeyInput { pressKey(Key.DirectionCenter) }
                return true
            }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        println("TV field $from did not reach action; route=$route")
        return false
    }

    @Test fun network_address_reaches_connect_action_with_Dpad() {
        screen(AppState())
        enter("network-address", "127.0.0.1:5555")
        val target = hasTestTag("network-connect") or hasText(compose.activity.getString(R.string.connect_try_loopback))
        assertTrue("Network editor traps D-pad navigation", traverse("network-address", target))
        assertTrue(actions.calls.single() in listOf("connect:127.0.0.1:5555", "loopback"))
    }

    @Test fun NTP_address_reaches_apply_or_check_with_Dpad() {
        screen()
        enter("ntp-address", "time.example")
        assertTrue("NTP editor traps D-pad navigation",
            traverse("ntp-address", hasTestTag("ntp-apply") or hasTestTag("ntp-check")))
        assertTrue(actions.calls.single() in listOf("apply:time.example", "check:time.example"))
    }

    @Test fun pairing_editors_each_reach_pair_action_with_Dpad() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        screen(AppState())
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        enter("pairing-address", "127.0.0.1:37123")
        enter("pairing-code", "123456")
        enter("pairing-connect-address", "127.0.0.1:40123")
        val reached = listOf("pairing-address", "pairing-code", "pairing-connect-address").associateWith {
            traverse(it, hasTestTag("pairing-connect"), activate = false)
        }
        assertTrue("Pairing editors trap D-pad navigation: $reached", reached.values.all { it })
        compose.onNodeWithTag("pairing-connect").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("pair:127.0.0.1:37123:123456:127.0.0.1:40123"), actions.calls)
    }

    @Test fun timezone_search_reaches_apply_with_Dpad() {
        screen()
        compose.onNodeWithTag("section-timezone").performScrollTo().performClick()
        enter("time-zone-search", "Europe/Moscow")
        assertTrue("Time zone editor traps D-pad navigation", traverse("time-zone-search", hasTestTag("time-zone-apply")))
        assertEquals(listOf("zone:Europe/Moscow"), actions.calls)
    }
}
