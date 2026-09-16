package com.civisrom.tvtimefixer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.MainActivity
import com.civisrom.tvtimefixer.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the real Activity actions, not a replacement AppActions implementation. */
class NetworkActivityIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun real_connection_terminal_cancellation_and_reconnect_through_the_UI() {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("real_ui_address")
        assumeTrue("Requires an explicitly supplied disposable ADB target", address != null)
        val pairing = args.getString("real_ui_pairing_address")
        if (pairing != null) {
            compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
            enter("pairing-address", pairing)
            enter("pairing-code", checkNotNull(args.getString("real_ui_pairing_code")))
            enter("pairing-connect-address", checkNotNull(address))
            click("pairing-connect")
        } else {
            enter("network-address", checkNotNull(address))
            click("network-connect")
        }
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.connect_state_connected, address))
                .fetchSemanticsNodes().isNotEmpty()
        }
        enter("ntp-address", "pool.ntp.org")
        compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsEnabled()
        click("terminal-open")
        compose.onNodeWithTag("terminal-warning-accept").performClick()
        execute("printf 'UI_%s_OK\\n' LIVE")
        compose.onNodeWithTag("terminal-list").performScrollToNode(hasText("UI_LIVE_OK", substring = true))
        compose.onNodeWithText("UI_LIVE_OK", substring = true).assertIsDisplayed()
        execute("echo error >&2; exit 7", 7)
        execute("printf 'UI_%s_OK\\n' RECOVERED")

        enter("terminal-input", "sleep 60; echo cancelled-command-finished")
        click("terminal-run")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("terminal-stop").fetchSemanticsNodes().isNotEmpty() }
        click("terminal-stop")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("terminal-run").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terminal-status").assertTextContains(compose.activity.getString(R.string.terminal_cancelled))
        execute("adb connect $address")
        execute("printf 'UI_%s_OK\\n' RECONNECTED")
        compose.onNodeWithTag("terminal-list").performScrollToNode(hasText("UI_RECONNECTED_OK", substring = true))
        compose.onNodeWithText("UI_RECONNECTED_OK", substring = true).assertIsDisplayed()
        click("terminal-back")
        compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsEnabled()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsNotEnabled()
        println("Network Activity: connection, terminal, cancellation, reconnect and recreation verified")
    }

    private fun enter(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        if (tag != "terminal-input") field.performScrollTo()
        field.performTextReplacement(value)
        if (tag != "terminal-input") field.performImeAction()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
    }

    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!tag.startsWith("terminal-") || tag == "terminal-open") node.performScrollTo()
        node.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }

    private fun execute(command: String, exitCode: Int = 0) {
        enter("terminal-input", command)
        click("terminal-run")
        val expected = compose.activity.getString(R.string.terminal_complete, exitCode)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("terminal-status") and hasText(expected, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
