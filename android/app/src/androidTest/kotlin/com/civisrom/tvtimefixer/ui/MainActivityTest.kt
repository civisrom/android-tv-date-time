package com.civisrom.tvtimefixer.ui

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300, 3_000)
        val file = File(instrumentation.targetContext.filesDir, "ui-screenshots/native-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        checkNotNull(bitmap)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
        try {
            compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
            compose.onNodeWithTag("ntp-address").performImeAction()
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("favorite-ntp-save").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("favorite-ntp-save").performScrollTo().performClick()
            try {
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-name").fetchSemanticsNodes().isNotEmpty() }
                lateinit var dialogView: View
                onView(isRoot()).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    dialogView = checkNotNull(view)
                }
                compose.onNodeWithTag("favorite-name").performClick()
                compose.waitUntil(5_000) {
                    ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                compose.onNodeWithTag("favorite-name").performImeAction()
                compose.waitUntil(5_000) {
                    ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
            } finally { screenshot("favorite-dialog") }
            compose.onNodeWithTag("favorite-confirm").assertIsFocused().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-ntp-pool.ntp.org").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorite-ntp-pool.ntp.org").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("favorite-ntp-pool.ntp.org").performScrollTo().performClick()
            compose.onNodeWithTag("ntp-address").assertTextContains("pool.ntp.org")
            compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
            screenshot("favorite-restored")
        } finally { app.favorites.write(com.civisrom.tvtimefixer.data.Favorites()) }
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
