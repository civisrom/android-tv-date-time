package com.civisrom.tvtimefixer.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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

    private var hideKeyboard: () -> Unit = {}

    private fun screen(mode: DeviceMode = DeviceMode.HANDHELD, busy: Boolean = false, scale: Float = 1f, width: Int = 360,
        target: String = "192.0.2.1:5555", name: String = "NVIDIA SHIELD") {
        compose.setContent {
            val context = LocalContext.current
            val baseConfig = LocalConfiguration.current
            val config = remember(baseConfig) { Configuration(baseConfig).apply { setLocale(Locale.forLanguageTag("ru")) } }
            val ru = remember(context, config) { context.createConfigurationContext(config) }
            inputMode = LocalInputModeManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            hideKeyboard = { keyboard?.hide() }
            CompositionLocalProvider(LocalContext provides ru, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme { Surface {
                    val state by session.state.collectAsState()
                    Box(Modifier.width(width.dp).fillMaxSize()) {
                        TerminalScreen(mode, state, target, busy, actions, { calls += "back" }, deviceName = name)
                    }
                } }
            }
        }
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("terminal-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun search(query: String) {
        // Native IME window changes are not synchronized by the Compose clock.
        val root = compose.activity.window.decorView
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun waitForKeyboard(visible: Boolean) {
            if (Build.VERSION.SDK_INT >= 30) {
                compose.waitUntil(5_000) {
                    compose.runOnUiThread { root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == visible }
                }
            }
            automation.waitForIdle(300, 3_000)
        }
        scroll("terminal-search").performClick().performTextReplacement(query)
        waitForKeyboard(true)
        compose.onNodeWithTag("terminal-search").performImeAction()
        waitForKeyboard(false)
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
        search("сервер времени")
        scroll("terminal-category-time").assertIsDisplayed()
        compose.onNodeWithTag("terminal-category-apps").assertDoesNotExist()
        compose.onNodeWithTag("terminal-example-time_1").assertDoesNotExist()
        scroll("terminal-category-time").performClick()
        scroll("terminal-example-time_1").assertIsDisplayed()
    }

    @Test fun search_does_not_offer_external_ADB_commands() {
        screen()
        compose.onNodeWithTag("terminal-tab-help").performClick()
        listOf("adb -L SOCKET", "adb forward", "adb start-server").forEach { query ->
            search(query)
            compose.onNodeWithTag("terminal-list").performScrollToNode(hasText("Команды не найдены"))
            compose.onNodeWithText("Команды не найдены").assertIsDisplayed()
            listOf("pc_options", "pc_ports", "pc_tools").forEach { category ->
                compose.onNodeWithTag("terminal-category-$category").assertDoesNotExist()
            }
        }
        assertTrue(calls.isEmpty())
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
        compose.onNodeWithTag("terminal-run").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle { assertEquals(listOf("run"), calls) }
    }

    @Test fun history_selection_only_edits_and_survives_navigation_between_tabs() {
        session.edit("getprop ro.product.model"); session.start("TV"); session.finish(0)
        session.edit("draft")
        screen()
        compose.onNodeWithTag("terminal-tab-history").performClick()
        compose.onNodeWithText("getprop ro.product.model").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("getprop ro.product.model")
        compose.runOnIdle { hideKeyboard() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("terminal-tab-files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terminal-tab-files").performClick()
        compose.onNodeWithTag("terminal-tab-console").performClick()
        compose.onNodeWithTag("terminal-input").assertTextContains("getprop ro.product.model")
        assertTrue(calls.isEmpty())
    }

    @Test fun execution_is_disabled_during_another_device_operation() {
        session.edit("reboot")
        screen(busy = true)
        compose.onNodeWithTag("terminal-run").assertIsNotEnabled()
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

    @Test fun scrolling_long_output_keeps_editor_run_and_clear_controls_in_place() {
        session.edit("logcat -d"); session.start("TV")
        repeat(200) { session.append("Line $it: " + "output ".repeat(20) + "\n") }
        session.finish(0)
        screen()
        val tags = listOf("terminal-input", "terminal-run", "terminal-clear", "terminal-follow")
        val positions = tags.associateWith { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        val input = positions.getValue("terminal-input")
        val model = compose.onNodeWithTag("terminal-device-name").fetchSemanticsNode().boundsInRoot
        val tabs = listOf("console", "help", "history", "files").map {
            compose.onNodeWithTag("terminal-tab-$it").fetchSemanticsNode().boundsInRoot
        }
        val output = compose.onNodeWithTag("terminal-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Editor must follow the device model", model.bottom <= input.top)
        assertTrue("Editor must follow all tabs", tabs.all { it.bottom <= input.top })
        assertEquals("Editor must use the full panel width", output.left, input.left, 1f)
        assertEquals("Editor must use the full panel width", output.right, input.right, 1f)
        assertTrue("Run must be on a separate row below the editor", input.bottom <= positions.getValue("terminal-run").top)
        listOf("terminal-clear", "terminal-follow").forEach {
            val control = positions.getValue(it)
            assertTrue("Output controls must follow Run and precede output",
                positions.getValue("terminal-run").bottom <= control.top && control.bottom <= output.top)
        }
        compose.onNodeWithTag("terminal-follow").performClick()
        compose.onNodeWithTag("terminal-list").performScrollToIndex(0)
        tags.forEach { assertEquals(positions[it], compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot) }
        compose.onNodeWithTag("terminal-list").performScrollToIndex(session.state.value.output.size)
        tags.forEach { assertEquals(positions[it], compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot) }
        compose.onNodeWithTag("terminal-clear").performClick()
        assertTrue(session.state.value.output.isEmpty())
        assertEquals("logcat -d", session.state.value.draft)
    }

    @Test fun manually_scrolling_output_pauses_following_new_lines() {
        session.edit("logcat -d"); session.start("TV")
        session.append("line\n".repeat(500)); session.finish(0)
        screen()
        compose.onNodeWithTag("terminal-follow").assertIsSelected()
        compose.onNodeWithTag("terminal-list").performTouchInput { swipeDown() }
        compose.onNodeWithTag("terminal-follow").assertIsNotSelected()
        compose.onNodeWithTag("terminal-clear").assertIsDisplayed()
    }

    @Test fun clearing_the_command_line_preserves_the_result_and_history() {
        session.edit("echo previous"); session.start("TV"); session.append("previous"); session.finish(0)
        session.edit("unfinished command")
        screen()
        compose.onNodeWithTag("terminal-clear-input").performClick()
        compose.runOnIdle {
            assertEquals("", session.state.value.draft)
            assertEquals("previous", session.state.value.output.single().text)
            assertEquals(listOf("echo previous"), session.state.value.history)
        }
        compose.onNodeWithTag("terminal-run").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-follow").assertIsSelected()
    }

    @Test fun connection_header_shows_the_connected_target_model() {
        screen()
        compose.onNodeWithTag("terminal-connection-status").assertTextEquals("Подключено")
        compose.onNodeWithTag("terminal-device-name").assertTextEquals("Модель устройства: NVIDIA SHIELD")
    }

    @Test fun disconnected_header_does_not_show_a_stale_model_or_allow_download() {
        screen(target = "")
        compose.onNodeWithTag("terminal-connection-status").assertTextEquals("Не подключено")
        compose.onNodeWithTag("terminal-device-name").assertDoesNotExist()
        compose.onNodeWithTag("terminal-tab-files").performClick()
        scroll("terminal-download").assertIsNotEnabled()
    }

    @Test fun completed_small_upload_shows_exact_bytes_and_the_remote_directory() {
        session.edit("adb push tiny.txt /sdcard/Download/tiny.txt"); session.start("TV")
        session.transferring("/sdcard/Download/tiny.txt", "tiny.txt", false)
        session.progress(684); session.finish(0)
        screen()
        scroll("terminal-transfer-size").assertTextEquals("Передано: 684 Б")
        scroll("terminal-transfer-path").assertTextContains("/sdcard/Download/tiny.txt", substring = true)
        scroll("terminal-transfer-result").assertTextContains("Файл отправлен", substring = true)
    }

    @Test fun download_requires_a_file_path_and_explicit_confirmation() {
        screen()
        compose.onNodeWithTag("terminal-tab-files").performClick()
        scroll("terminal-download").performClick()
        compose.onNodeWithTag("terminal-download-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-remote-path").performTextInput("/sdcard/Download/")
        compose.onNodeWithTag("terminal-download-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-remote-path").performTextReplacement("/sdcard/Download/my file.txt")
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("terminal-download-confirm").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle {
            assertEquals("adb pull '/sdcard/Download/my file.txt'", session.state.value.draft)
            assertEquals(listOf("run"), calls)
        }
    }

    @Test fun category_titles_and_history_rows_use_compact_left_aligned_text() {
        session.edit("echo short"); session.start("TV"); session.finish(0)
        session.edit("echo a longer command"); session.start("TV"); session.finish(0)
        screen()
        compose.onNodeWithTag("terminal-tab-history").performClick()
        val first = compose.onNodeWithText("echo a longer command", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("echo short", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 1f)
        val historyRow = compose.onNodeWithTag("terminal-history-echo a longer command").fetchSemanticsNode().boundsInRoot
        val nextRow = compose.onNodeWithTag("terminal-history-echo short").fetchSemanticsNode().boundsInRoot
        // Material reserves a 48 dp touch target around the shorter visible TextButton.
        val rowHeight = maxOf(historyRow.height, with(compose.density) { 48.dp.toPx() })
        assertEquals("History rows must not add spacing beyond the touch target", rowHeight, nextRow.top - historyRow.top, 1f)
        compose.onNodeWithTag("terminal-tab-help").performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        scroll("terminal-category-connection").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(androidx.compose.ui.text.style.TextAlign.Start, layouts.single().layoutInput.style.textAlign)
    }
}
