package com.civisrom.tvtimefixer.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.R
import com.civisrom.tvtimefixer.terminal.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalScreenTest {
    @get:Rule val compose = createComposeRule()
    private val session = TerminalSession()
    private val calls = mutableListOf<String>()
    private lateinit var inputMode: InputModeManager
    private val actions = object : TerminalActions {
        override fun edit(command: String) = session.edit(command)
        override fun run() { calls += "run" }
        override fun stop() { calls += "stop" }
        override fun clearOutput() = session.clearOutput()
        override fun clearHistory() = session.clearHistory()
        override fun importFiles() { calls += "import" }
        override fun exportFile(name: String) { calls += "export:$name" }
    }

    private fun screen(mode: DeviceMode = DeviceMode.HANDHELD, busy: Boolean = false, scale: Float = 1f, width: Int = 360) {
        compose.setContent {
            val context = LocalContext.current
            val baseConfig = LocalConfiguration.current
            val config = remember(baseConfig) { Configuration(baseConfig).apply { setLocale(Locale.forLanguageTag("ru")) } }
            val ru = remember(context, config) { context.createConfigurationContext(config) }
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalContext provides ru, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme { Surface {
                    val state by session.state.collectAsState()
                    Box(Modifier.requiredWidth(width.dp).fillMaxSize()) {
                        TerminalScreen(mode, state, "Target TV · 192.0.2.1:5555", busy, actions, { calls += "back" })
                    }
                } }
            }
        }
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("terminal-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun categories_start_collapsed_and_opening_one_closes_the_previous_category() {
        screen()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        compose.onNodeWithTag("terminal-example-time_0").assertDoesNotExist()
        scroll("terminal-category-time").performClick()
        scroll("terminal-example-time_0").assertIsDisplayed()
        scroll("terminal-category-connection").performClick()
        compose.onNodeWithTag("terminal-example-time_0").assertDoesNotExist()
        scroll("terminal-example-connection_0").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test fun insert_from_reference_fills_editor_without_running_and_places_cursor_at_the_end() {
        screen()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        scroll("terminal-category-time").performClick()
        scroll("terminal-insert-time_1").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("settings get global ntp_server")
        compose.onNodeWithTag("terminal-input").assertIsFocused()
        assertTrue(calls.isEmpty())
        val range = compose.onNodeWithTag("terminal-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("settings get global ntp_server".length, range.start)
        assertEquals(range.start, range.end)
    }

    @Test fun search_matches_localized_description_and_keeps_results_collapsed() {
        screen()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        scroll("terminal-search").performTextInput("сервер времени")
        scroll("terminal-category-time").assertExists()
        compose.onNodeWithTag("terminal-category-apps").assertDoesNotExist()
        compose.onNodeWithTag("terminal-example-time_1").assertDoesNotExist()
        scroll("terminal-category-time").performClick()
        scroll("terminal-example-time_1").assertIsDisplayed()
    }

    @Test fun copy_reference_command_does_not_change_the_editor_or_execute() {
        session.edit("existing command")
        screen()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        scroll("terminal-category-time").performClick()
        scroll("terminal-copy-time_0").performClick()
        compose.runOnIdle {
            val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("date", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertEquals("existing command", session.state.value.draft)
        assertTrue(calls.isEmpty())
    }

    @Test fun selected_APK_prepares_target_installation_without_automatically_executing_it() {
        session.refreshFiles(listOf("my app.apk"))
        screen()
        compose.onNodeWithTag("terminal-tab-files").performClick()
        scroll("terminal-install-my app.apk").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("adb install -r 'my app.apk'")
        assertTrue(calls.isEmpty())
        // Verify explicit execution separately from the IME transition caused by focusing the draft.
        scroll("terminal-run").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle { assertEquals(listOf("run"), calls) }
    }

    @Test fun history_selection_only_edits_and_survives_navigation_between_tabs() {
        session.edit("getprop ro.product.model"); session.start("TV"); session.finish(0)
        session.edit("draft")
        screen()
        compose.onNodeWithTag("terminal-tab-history").performClick()
        compose.onNodeWithText("getprop ro.product.model").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("getprop ro.product.model")
        compose.onNodeWithTag("terminal-tab-files").performClick()
        compose.onNodeWithTag("terminal-tab-console").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("getprop ro.product.model")
        assertTrue(calls.isEmpty())
    }

    @Test fun execution_is_disabled_during_another_device_operation() {
        session.edit("reboot")
        screen(busy = true)
        scroll("terminal-run").assertIsNotEnabled()
    }

    @Test fun stop_is_available_from_help_and_files_while_the_command_runs() {
        session.edit("logcat"); session.start("TV")
        screen()
        compose.onNodeWithTag("terminal-stop").assertIsDisplayed().performClick()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        compose.onNodeWithTag("terminal-stop").assertIsDisplayed()
        compose.onNodeWithTag("terminal-tab-files").performClick()
        compose.onNodeWithTag("terminal-stop").assertIsDisplayed()
        scroll("terminal-import").assertIsNotEnabled()
        assertEquals(listOf("stop"), calls)
    }

    @Test fun TV_back_button_is_focused_and_activated_with_remote_keys() {
        screen(mode = DeviceMode.TELEVISION, width = 960)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("terminal-back").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("terminal-back").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("back"), calls)
    }

    @Test fun TV_output_can_receive_focus_and_pause_automatic_scrolling() {
        session.edit("logcat"); session.start("TV"); session.append("live output\n")
        screen(mode = DeviceMode.TELEVISION, width = 960)
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        scroll("terminal-block-0").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("terminal-block-0").assertIsFocused()
        compose.onNodeWithTag("terminal-follow").assertIsNotSelected()
        compose.onNodeWithTag("terminal-stop").assertIsDisplayed()
    }

    @Test fun large_font_keeps_APK_actions_scrollable_on_a_narrow_screen() {
        session.refreshFiles(listOf("app.apk"))
        screen(scale = 2f, width = 480)
        compose.onNodeWithTag("terminal-tab-files").performClick()
        scroll("terminal-install-app.apk").assertIsDisplayed()
        scroll("terminal-export-app.apk").assertIsDisplayed().performClick()
        assertEquals(listOf("export:app.apk"), calls)
    }

    @Test fun remote_stderr_and_nonzero_exit_are_shown_without_losing_the_output() {
        session.edit("cmd bad"); session.start("TV"); session.append("Permission denied", true); session.finish(17)
        screen()
        scroll("terminal-status").assertTextContains("17", substring = true)
        scroll("terminal-output-0").assertTextContains("Permission denied")
    }
}
