package com.civisrom.tvtimefixer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.MainActivity
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.detectDeviceMode
import com.civisrom.tvtimefixer.R
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Настоящие Application, Activity, lifecycle и обработчики действий установленного APK. */
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun stalled_connection_can_be_cancelled_and_retried_without_leaving_a_socket_open() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 15_000
            val accepted = List(2) { java.util.concurrent.CountDownLatch(1) }
            val closed = List(2) { java.util.concurrent.CountDownLatch(1) }
            val peer = kotlin.concurrent.thread(isDaemon = true) {
                try {
                    repeat(2) { attempt ->
                        listener.accept().use { socket ->
                            socket.soTimeout = 15_000
                            accepted[attempt].countDown()
                            val input = socket.getInputStream()
                            while (input.read() != -1) { /* Consume CNXN, never authorize. */ }
                            closed[attempt].countDown()
                        }
                    }
                } catch (_: java.io.IOException) { /* Teardown closes the fixture after a failed assertion. */ }
            }
            compose.onNodeWithTag("connection-cancel").assertDoesNotExist()
            compose.onNodeWithTag("network-address").performScrollTo()
                .performTextReplacement("127.0.0.1:${listener.localPort}")
            repeat(2) { attempt ->
                compose.onNodeWithTag("network-connect").performScrollTo()
                    .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                assertTrue("Fixture was not contacted", accepted[attempt].await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNodeWithTag("connection-cancel").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(3_000) {
                    runCatching { compose.onNodeWithTag("network-connect").assertIsEnabled() }.isSuccess
                }
                assertTrue("Cancelled connection left its socket open", closed[attempt].await(1, java.util.concurrent.TimeUnit.SECONDS))
                compose.onNodeWithTag("connection-cancel").assertDoesNotExist()
                compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsNotEnabled()
            }
            peer.join(1_000)
        }
    }

    @Test fun terminal_opens_from_main_and_returns_focus_to_its_entry() {
        compose.onNodeWithTag("terminal-open").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-warning-accept").performClick()
        compose.onNodeWithTag("terminal-screen").assertIsDisplayed()
        compose.onNodeWithTag("terminal-input").performTextInput("getprop ro.product.model")
        compose.onNodeWithTag("terminal-back").performClick()
        compose.onNodeWithTag("terminal-open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("terminal-open").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("getprop ro.product.model")
        screenshot("terminal-draft")
    }

    @Test fun terminal_without_a_connection_reports_failure_without_running_locally() {
        compose.onNodeWithTag("terminal-open").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-warning-accept").performClick()
        compose.onNodeWithTag("terminal-input").performTextInput("echo terminal-test")
        // The system IME moves Run after Compose text input has already returned.
        if (Build.VERSION.SDK_INT >= 30) {
            val root = compose.activity.window.decorView
            compose.waitUntil(5_000) {
                compose.runOnUiThread { root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        compose.onNodeWithTag("terminal-run").performClick()
        val error = compose.activity.getString(R.string.terminal_connection_error)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("terminal-status") and hasText(error, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("terminal-list").performScrollToNode(hasTestTag("terminal-status"))
        compose.onNodeWithTag("terminal-status").assertTextContains(error)
        screenshot("terminal-disconnected")
    }

    @Test fun terminal_warning_can_be_cancelled_and_is_not_repeated_within_the_session() {
        compose.onNodeWithTag("terminal-open").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_warning_body)).assertIsDisplayed()
        screenshot("terminal-warning")
        compose.onNodeWithTag("terminal-warning-cancel").performClick()
        compose.onNodeWithTag("terminal-screen").assertDoesNotExist()
        compose.onNodeWithTag("terminal-open").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-warning-accept").performClick()
        compose.onNodeWithTag("terminal-back").performClick()
        compose.onNodeWithTag("terminal-open").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-warning-accept").assertDoesNotExist()
        compose.onNodeWithTag("terminal-screen").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 3_000)
        val file = File(instrumentation.targetContext.filesDir, "ui-screenshots/native-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        checkNotNull(bitmap)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            checkScreenshotContent(bitmap)
        } finally { bitmap.recycle() }
    }

    @Test fun installed_APK_detects_the_actual_form_factor_and_page_size() {
        val args = InstrumentationRegistry.getArguments()
        args.getString("expected_tv")?.let {
            assertEquals(it.toBoolean(), detectDeviceMode(compose.activity) == DeviceMode.TELEVISION)
        }
        args.getString("expected_page_size")?.let {
            assertEquals(it.toLong(), Os.sysconf(OsConstants._SC_PAGESIZE))
        }
        compose.onNodeWithTag("main-content").assertExists()
        compose.onNodeWithTag("network-address").performScrollTo().assertIsDisplayed()
        screenshot("startup")
    }

    @Test fun invalid_connection_runs_real_validation_and_is_recoverable_after_resume() {
        compose.onNodeWithTag("network-address").performScrollTo().performTextInput("invalid:0")
        compose.onNodeWithTag("network-connect").performScrollTo().performClick()
        val error = compose.activity.getString(R.string.error_invalid_address)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        screenshot("invalid-connection")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("network-connect").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("network-address").assertTextContains("invalid:0")
    }

    @Test fun diagnostics_can_be_opened_and_closed_with_remote_keys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Настоящее событие пульта переводит старый Android из touch mode.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("diagnostics-open").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("diagnostics-open").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("diagnostics-back").assertIsDisplayed()
        screenshot("diagnostics")
        compose.onNodeWithTag("diagnostics-back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("diagnostics-open").assertIsDisplayed().assertIsFocused()
    }

    @Test fun recreating_the_activity_preserves_editable_address_without_claiming_a_connection() {
        compose.onNodeWithTag("network-address").performScrollTo().performTextInput("192.0.2.1:5555")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("network-address").performScrollTo().assertTextContains("192.0.2.1:5555")
        compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsNotEnabled()
        screenshot("recreated")
    }

    @Test fun favorite_server_is_saved_by_the_real_activity_and_restored_after_recreation() {
        val app = compose.activity.application as com.civisrom.tvtimefixer.TimeFixerApplication
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            fun waitForKeyboard(visible: Boolean, root: View = compose.activity.window.decorView) {
                compose.waitUntil(5_000) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        // Read the actual input window; the accessibility window list can lag on API 31.
                        compose.runOnUiThread { root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == visible }
                    } else {
                        // Legacy Insets only estimate IME visibility and miss floating dialogs.
                        hasLegacyImeWindow(automation) == visible
                    }
                }
                automation.waitForIdle(300, 3_000)
            }
            try {
                compose.waitUntil(5_000) {
                    compose.runOnUiThread { compose.activity.window.decorView.hasWindowFocus() }
                }
                // Semantics text input alone does not promise to open the software keyboard.
                compose.onNodeWithTag("ntp-address").performScrollTo().performClick().performTextInput("pool.ntp.org")
                waitForKeyboard(true)
                compose.onNodeWithTag("ntp-address").performImeAction()
                waitForKeyboard(false)
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("favorite-ntp-save").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("favorite-ntp-save").performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-name").fetchSemanticsNodes().isNotEmpty() }
                var dialogRoot: View? = null
                onView(isRoot()).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    dialogRoot = view
                }
                val inputRoot = checkNotNull(dialogRoot)
                compose.onNodeWithTag("favorite-name").performClick()
                waitForKeyboard(true, inputRoot)
                compose.onNodeWithTag("favorite-name").performImeAction()
                waitForKeyboard(false, inputRoot)
            } finally { screenshot("favorite-dialog") }
            compose.onNodeWithTag("favorite-confirm").assertIsFocused().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-ntp-pool.ntp.org").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-ntp-pool.ntp.org").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("favorite-ntp-pool.ntp.org").performScrollTo().performClick()
            compose.onNodeWithTag("ntp-address").assertTextContains("pool.ntp.org")
            compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
            screenshot("favorite-restored")
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            app.favorites.write(com.civisrom.tvtimefixer.data.Favorites())
        }
    }

    @Test fun TV_setup_is_navigable_with_actual_remote_events_and_survives_resume() {
        org.junit.Assume.assumeTrue(detectDeviceMode(compose.activity) == DeviceMode.TELEVISION)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("setup-open").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("setup-screen").assertIsDisplayed()
        compose.onNodeWithTag("setup-next").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("setup-next").performScrollTo().assertIsFocused()
        screenshot("tv-setup-debugging")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("setup-connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-next").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("setup-open").assertIsDisplayed().assertIsFocused()
    }
}
