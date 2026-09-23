package com.civisrom.tvtimefixer.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.MainActivity
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.adb.*
import com.civisrom.tvtimefixer.data.*
import com.civisrom.tvtimefixer.device.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Runs actual Activity actions; opt-in and serialized with every other device-settings test. */
class TimeFeaturesActivityIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun real_UI_profiles_persist_restore_monitor_lifecycle_and_clipboard() {
        val args = InstrumentationRegistry.getArguments()
        val raw = args.getString("real_time_features_address")
        assumeTrue("Requires disposable authorized target and NTP fixtures", args.getString("real_time_features") == "1" && raw != null)
        val address = checkNotNull(parseDeviceAddress(checkNotNull(raw)))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = File(context.noBackupFilesDir, "time-settings.bin")
        val previousStore = local.takeIf { it.exists() }?.readBytes()
        val external = DeviceConnector(KadbAdbClientFactory(5_000, 8_000))
        assertEquals(ConnectionState.Connected(address), external.connect(address))
        val repository = TimeSettingsRepository(checkNotNull(external.activeClient))
        val original = repository.capture()
        assertTrue(original.capturable)
        val identity = checkNotNull(original.identity)
        try {
            assertTrue(repository.apply(identity, TimeSettings(mapOf(TimeSetting.AUTO_TIME to "0"))).confirmed)
            external.disconnect()
            connect(address.toString())
            openTools()
            click("time-snapshot-save"); confirm()
            awaitReady()
            assertNotNull(TimeSettingsStore(local).read().snapshots.firstOrNull { it.identity == identity })

            val name = "Live profile ${SystemClock.elapsedRealtime()}"
            enter("time-profile-name", name)
            click("time-profile-save")
            compose.onNodeWithTag("time-preview-cancel").performClick()
            assertTrue(TimeSettingsStore(local).read().profiles.none { it.name == name })
            click("time-profile-save"); confirm(); awaitReady()
            assertTrue(TimeSettingsStore(local).read().profiles.any { it.name == name && it.saved.identity == identity })

            enter("time-ntp-list", "127.0.0.2\n::1")
            click("time-ntp-list-apply"); confirm(); awaitReady()
            compose.onNodeWithText(compose.activity.getString(R.string.time_settings_confirmed)).assertExists()
            click("time-snapshot-restore")
            compose.onNodeWithTag("time-preview-cancel").performClick()

            compose.activityRule.scenario.recreate()
            connect(address.toString())
            openTools()
            compose.onNodeWithTag("time-snapshot-restore").performScrollTo().assertIsEnabled()
            val profileIndex = TimeSettingsStore(local).read().profiles.filter { it.saved.identity == identity }.indexOfFirst { it.name == name }
            assertTrue(profileIndex >= 0)
            click("time-profile-apply-$profileIndex"); confirm(); awaitReady()
            compose.onNodeWithText(compose.activity.getString(R.string.time_settings_confirmed)).assertExists()
            click("time-profile-delete-$profileIndex"); confirm(); awaitReady()
            assertTrue(TimeSettingsStore(local).read().profiles.none { it.name == name })

            // Use the real loopback reference for a quick monitoring sample.
            enter("time-ntp-list", "::1")
            click("time-ntp-list-apply"); confirm(); awaitReady()
            click("time-monitor-start")
            awaitSample()
            compose.onNodeWithTag("time-monitor-stop").performScrollTo().assertIsEnabled()
            click("time-monitor-stop")
            compose.onNodeWithTag("time-monitor-stop").assertIsNotEnabled()
            click("time-monitor-start")
            awaitSample()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("time-monitor-stop").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(compose.activity.getString(R.string.time_monitor_background)).assertExists()
            click("time-monitor-start")
            awaitSample()
            compose.onNodeWithText(compose.activity.getString(R.string.connect_disconnect)).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            compose.waitUntil(20_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.connect_state_disconnected)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("time-monitor-stop").performScrollTo().assertIsNotEnabled()
            println("Time feature Activity: persistent profiles, preview cancel, explicit apply/delete, Stop/background/disconnect PASS")

            connect(address.toString())
            // The section retains its expansion when reconnecting.
            click("time-snapshot-restore"); confirm(); awaitReady()
            click("diagnostics-open")
            compose.onNodeWithTag("diagnostics-events").performScrollToNode(hasTestTag("diagnostics-copy"))
            compose.onNodeWithTag("diagnostics-copy").performClick()
            compose.runOnIdle {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
                assertTrue(report.contains("privacy=addresses_and_device_identifiers_omitted"))
                assertFalse(report.contains(address.toString()))
                assertFalse(report.contains(identity.digest))
                assertFalse(report.contains(name))
                assertTrue(report.contains("operation=TIME_SETTINGS"))
            }
            println("Time feature Activity: actual clipboard exports the redacted allowlist PASS")
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            external.disconnect()
            assertEquals(ConnectionState.Connected(address), external.connect(address))
            val finalRepository = TimeSettingsRepository(checkNotNull(external.activeClient))
            val restored = finalRepository.apply(identity, original.settings)
            val actual = finalRepository.read()
            external.disconnect()
            if (previousStore == null) local.delete() else local.writeBytes(previousStore)
            assertTrue("Final restoration: $restored", restored.confirmed)
            assertEquals(original.settings, actual.settings)
        }
    }

    private fun connect(address: String) {
        enter("network-address", address)
        click("network-connect")
        compose.waitUntil(45_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.connect_state_connected, address)).fetchSemanticsNodes().isNotEmpty() }
        // Connected status can arrive before the bound store has been loaded.
        compose.waitUntil(45_000) { runCatching { compose.onNodeWithTag("network-connect").assertIsEnabled() }.isSuccess }
    }
    private fun openTools() {
        if (compose.onAllNodesWithTag("time-tools-refresh").fetchSemanticsNodes().isEmpty()) click("section-time-tools")
    }
    private fun awaitSample() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("time-monitor-sample-0").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitReady() {
        compose.waitUntil(45_000) { runCatching { compose.onNodeWithTag("time-tools-refresh").assertIsEnabled() }.isSuccess }
    }
    private fun enter(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
        // Hide IME without delivering Back to a preview dialog.
        compose.activity.runOnUiThread {
            val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
    }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun confirm() { compose.onNodeWithTag("time-preview-confirm").performClick() }
}
