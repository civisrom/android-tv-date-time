package com.civisrom.tvtimefixer.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
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
import com.civisrom.tvtimefixer.data.*
import com.civisrom.tvtimefixer.device.*
import com.civisrom.tvtimefixer.diagnostics.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimeToolsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val identity = TimeDeviceIdentity("a".repeat(64), DeviceIdentityKind.ANDROID_ID)
    private val settings = TimeSettings(mapOf(TimeSetting.NTP to "ntp://127.0.0.2|ntp://[::1]",
        TimeSetting.AUTO_TIME to "0", TimeSetting.AUTO_TIME_ZONE to "null", TimeSetting.TIME_ZONE to "Europe/Moscow"), false)
    private val saved = SavedTimeSettings(identity, 1_800_000_000_000, settings)
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(AppState(connection = ConnectionState.Connected(DeviceAddress("127.0.0.1", 5555)),
        deviceInfo = DeviceInfo(apiLevel = "36"), timeTools = TimeToolsState(identity, saved,
            listOf(TimeProfile("Living room", saved)), TimeSettingsRead(identity, settings,
                TimeSetting.entries.associateWith { TimeSettingStatus.VERIFIED }, 36))))
    private lateinit var inputMode: InputModeManager
    private val actions = object : AppActions by ScreenActions() {
        override fun restoreTimeSnapshot() { calls += "restore" }
        override fun saveTimeSnapshot(replace: Boolean) { calls += "snapshot:$replace" }
        override fun saveTimeProfile(name: String) { calls += "save:$name" }
        override fun applyTimeProfile(name: String) { calls += "apply:$name" }
        override fun removeTimeProfile(name: String) { calls += "delete:$name" }
        override fun applyNtpList(hosts: List<String>) { calls += "ntp:${hosts.joinToString("|")}" }
        override fun startClockMonitor() {
            calls += "start"
            state = state.copy(timeTools = state.timeTools.copy(monitor = ClockMonitorState(running = true)))
        }
        override fun stopClockMonitor() {
            calls += "stop"
            state = state.copy(timeTools = state.timeTools.copy(monitor = ClockMonitorState(ended = ClockMonitorEnd.USER)))
        }
    }

    private fun screen(width: Int = 320, height: Int = 480, scale: Float = 2f, mode: DeviceMode = DeviceMode.HANDHELD) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme {
                    Column(Modifier.width(width.dp).requiredHeight(height.dp).verticalScroll(rememberScrollState())
                        .padding(12.dp)) { TimeToolsSection(state, actions, mode) }
                }
            }
        }
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()

    @Test fun short_screen_double_font_restore_preview_cancel_and_confirm() {
        screen()
        click("time-snapshot-restore")
        compose.onNodeWithTag("time-preview-values").assertExists()
        compose.onNode(hasText("${compose.activity.getString(R.string.time_field_zone)}: Europe/Moscow") and
            hasAnyAncestor(hasTestTag("time-preview-values"))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-preview-cancel").assertIsDisplayed().performClick()
        assertTrue(calls.isEmpty())
        click("time-snapshot-restore")
        compose.onNodeWithTag("time-preview-confirm").assertIsDisplayed().performClick()
        assertEquals(listOf("restore"), calls)
    }

    @Test fun profile_apply_delete_and_snapshot_replacement_require_explicit_confirmation() {
        screen(scale = 1.3f)
        click("time-profile-apply-0")
        compose.onNodeWithTag("time-preview-cancel").performClick()
        assertTrue(calls.isEmpty())
        click("time-profile-apply-0")
        compose.onNodeWithTag("time-preview-confirm").performClick()
        click("time-profile-delete-0")
        compose.onNodeWithTag("time-preview-confirm").performClick()
        click("time-snapshot-save")
        compose.onNodeWithTag("time-preview-confirm").performClick()
        assertEquals(listOf("apply:Living room", "delete:Living room", "snapshot:true"), calls)
    }

    @Test fun NTP_list_validates_API_limit_duplicates_and_mixed_IPv6_before_preview() {
        screen(scale = 1f)
        val field = compose.onNodeWithTag("time-ntp-list")
        field.performScrollTo().performTextReplacement("127.0.0.2\n::1")
        compose.onNodeWithTag("time-ntp-list-apply").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state = state.copy(deviceInfo = DeviceInfo(apiLevel = "33")) }
        compose.onNodeWithTag("time-ntp-list-apply").assertIsNotEnabled()
        field.performScrollTo().performTextReplacement("time.example\ntime.example")
        compose.runOnIdle { state = state.copy(deviceInfo = DeviceInfo(apiLevel = "36")) }
        compose.onNodeWithTag("time-ntp-list-apply").performScrollTo().assertIsNotEnabled()
        field.performScrollTo().performTextReplacement("127.0.0.2\n::1")
        click("time-ntp-list-apply")
        compose.onNodeWithText("ntp://127.0.0.2|ntp://[::1]").assertExists()
        compose.onNodeWithTag("time-preview-confirm").performClick()
        assertEquals(listOf("ntp:127.0.0.2|::1"), calls)
    }

    @Test fun short_TV_keyboard_focus_reaches_monitor_start_and_stop() {
        screen(width = 640, height = 360, scale = 1.5f, mode = DeviceMode.TELEVISION)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        fun activate(tag: String) {
            val button = compose.onNodeWithTag(tag)
            button.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.waitUntil(5_000) { button.isDisplayed() }
            button.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        }
        activate("time-monitor-start")
        compose.onNodeWithTag("time-monitor-start").assertIsNotEnabled()
        activate("time-monitor-stop")
        compose.onNodeWithTag("time-monitor-stop").assertIsNotEnabled()
        assertEquals(listOf("start", "stop"), calls)
    }

    @Test fun TV_sequential_Dpad_route_reaches_monitor_without_touch_or_direct_focus_jump() {
        screen(width = 640, height = 360, scale = 1.3f, mode = DeviceMode.TELEVISION)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("time-tools-refresh").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        val start = compose.onNodeWithTag("time-monitor-start")
        var reached = false
        val route = mutableListOf<String>()
        for (step in 0 until 80) {
            val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString {
                it.config.getOrNull(SemanticsProperties.TestTag) ?: "node-${it.id}"
            }
            route += focused
            println("TimeTools D-pad step=$step focus=$focused")
            reached = runCatching { start.assertIsFocused() }.isSuccess
            if (reached) break
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        }
        assertTrue("D-pad traversal never reached Start; route=$route", reached)
        start.assertIsDisplayed().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("start"), calls)
    }

    @Test fun phone_hardware_keyboard_keeps_multiline_cursor_navigation() {
        screen(scale = 1f)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val field = compose.onNodeWithTag("time-ntp-list")
        val text = "first.example\nsecond.example"
        field.performScrollTo().performClick().performTextReplacement(text)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        field.assertIsFocused()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
        assertEquals(text.length, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].start)
        field.performKeyInput { pressKey(Key.DirectionUp) }
        field.assertIsFocused()
        assertTrue(field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].start < text.length)
        field.performKeyInput { pressKey(Key.DirectionDown) }
        field.assertIsFocused()
        assertEquals(text.length, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].start)
        assertTrue(calls.isEmpty())
    }

    @Test fun busy_connection_has_visible_progress_at_the_start_of_the_time_tools_section() {
        state = state.copy(busy = true, operation = Operation.CONNECT_NETWORK)
        screen(scale = 1f)
        compose.onNodeWithTag("time-tools-progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-tools-refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time-snapshot-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missing_identity_disables_mutations_but_refresh_remains_available() {
        state = state.copy(timeTools = TimeToolsState())
        screen(scale = 1f)
        compose.onNodeWithTag("time-tools-refresh").performScrollTo().assertIsEnabled()
        for (tag in listOf("time-snapshot-save", "time-snapshot-restore", "time-profile-save", "time-ntp-list-apply"))
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test fun a_fresh_default_server_attempt_does_not_claim_an_old_measurement() {
        val check = DeviceTimeCheck(DeviceTimeStatus.SYSTEM_DEFAULT, observedAtElapsedMillis = android.os.SystemClock.elapsedRealtime())
        compose.setContent { MaterialTheme { DeviceTimeCard(check) } }
        compose.onNodeWithTag("time-check-status").assertTextContains(compose.activity.getString(R.string.time_check_system_default))
        compose.onNodeWithTag("time-check-stale").assertDoesNotExist()
    }

    @Test fun diagnostic_copy_is_redacted_and_missing_document_picker_keeps_copy_available() {
        val secret = "PRIVATE_DEVICE_ID 192.0.2.123 secret-command"
        val history = DiagnosticSnapshot(events = listOf(DiagnosticEvent(1, 123, Operation.TIME_SETTINGS,
            Outcome.FAILED, details = secret)))
        var message by mutableStateOf<Int?>(null)
        compose.setContent { MaterialTheme { DiagnosticsScreen(DeviceMode.HANDHELD, history, null, {}, {},
            onExport = { message = R.string.time_export_unavailable }, exportMessage = message) } }
        compose.onNodeWithTag("diagnostics-events").performScrollToNode(hasTestTag("diagnostics-export"))
        compose.onNodeWithTag("diagnostics-export").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.time_export_unavailable)).assertExists()
        compose.onNodeWithTag("diagnostics-events").performScrollToNode(hasTestTag("diagnostics-copy"))
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("operation=TIME_SETTINGS"))
            for (value in secret.split(' ')) assertFalse(copied.contains(value))
        }
    }
}
