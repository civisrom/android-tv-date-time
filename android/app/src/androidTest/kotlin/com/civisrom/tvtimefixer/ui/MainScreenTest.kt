package com.civisrom.tvtimefixer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.usb.UsbManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.DiscoveredDevice
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.adb.UsbDevices
import com.civisrom.tvtimefixer.adb.ACTION_USB_SYSTEM_STATE
import com.civisrom.tvtimefixer.adb.usbSystemState
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.NtpProbeResult
import com.civisrom.tvtimefixer.data.ScanProgress
import com.civisrom.tvtimefixer.device.DeviceTimeCheck
import com.civisrom.tvtimefixer.device.DeviceTimeStatus
import com.civisrom.tvtimefixer.device.DeviceInfo
import com.civisrom.tvtimefixer.device.TimeZoneFailure
import com.civisrom.tvtimefixer.device.TimeZoneRestoration
import com.civisrom.tvtimefixer.device.TimeZoneUpdateResult
import com.civisrom.tvtimefixer.diagnostics.DiagnosticEvent
import com.civisrom.tvtimefixer.diagnostics.DiagnosticIssue
import com.civisrom.tvtimefixer.diagnostics.DiagnosticSnapshot
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.Outcome
import com.civisrom.tvtimefixer.diagnostics.UsbSystemState
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass

private class ScreenActions : AppActions {
    val calls = mutableListOf<String>()
    var onScan: () -> Unit = {}
    var onClearScan: () -> Unit = {}
    override fun connect(address: String) { calls += "connect:$address" }
    override fun connectLoopback() { calls += "loopback" }
    override fun disconnect() { calls += "disconnect" }
    override fun pairAndConnect(pairingAddress: String, code: String, connectAddress: String) {
        calls += "pair:$pairingAddress:$code:$connectAddress"
    }
    override fun checkNtpServer(server: String) { calls += "check:$server" }
    override fun applyNtpServer(server: String) { calls += "apply:$server" }
    override fun verifyDeviceTime() { calls += "verify-time" }
    override fun applyTimeZone(zoneId: String) { calls += "zone:$zoneId" }
    override fun scanNtpServers() { calls += "scan"; onScan() }
    override fun cancelNtpScan() { calls += "cancel-scan" }
    override fun clearNtpScanResults() { calls += "clear-scan"; onClearScan() }
    override fun refreshDeviceInfo() { calls += "info" }
    override fun requestDiscoveryPermission() { calls += "permission" }
    override fun refreshUsbDevices() { calls += "usb-list" }
    override fun connectUsb(address: UsbDeviceAddress) { calls += "usb-connect" }
}

class MainScreenTest {
    companion object {
        private var originalAccessibilityFlags = 0

        @JvmStatic @BeforeClass fun inspectTextSelectionWindows() {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            originalAccessibilityFlags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }

        @JvmStatic @AfterClass fun restoreWindowInspection() {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        }
    }

    @get:Rule val compose = createComposeRule()
    private val actions = ScreenActions()
    private lateinit var inputModeManager: InputModeManager
    private lateinit var hostView: View
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun russianString(res: Int, vararg args: Any): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        return context.createConfigurationContext(config).getString(res, *args)
    }
    private val connected = AppState(connection = ConnectionState.Connected(DeviceAddress("192.168.1.2", 5555)))

    @Test fun checking_a_previous_connection_hides_connected_status_and_disables_time_changes() {
        val address = DeviceAddress("192.168.1.2", 5555)
        screen(connected.copy(connection = ConnectionState.Checking(address), busy = true,
            currentNtpServer = "pool.ntp.org", timeCheck = DeviceTimeCheck(DeviceTimeStatus.MATCH)))
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.connect_state_checking, address.toString()))
            .assertIsDisplayed()
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.connect_state_connected, address.toString()))
            .assertDoesNotExist()
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
        compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
        compose.onNodeWithTag("time-check-result").assertDoesNotExist()
    }

    @Test fun verifying_time_is_a_separate_read_action() {
        screen(connected.copy(currentNtpServer = "pool.ntp.org"))
        compose.onNodeWithTag("time-check").performScrollTo().performClick()
        assertEquals(listOf("verify-time"), actions.calls)
    }

    @Test fun time_verification_is_disabled_during_an_operation() {
        screen(connected.copy(busy = true, operation = Operation.CHECK_TIME))
        compose.onNodeWithTag("time-check").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time-check-working").performScrollTo().assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun a_disconnected_device_never_shows_an_old_time_confirmation() {
        screen(AppState(timeCheck = DeviceTimeCheck(DeviceTimeStatus.MATCH)))
        compose.onNodeWithTag("time-check").assertDoesNotExist()
        compose.onNodeWithTag("time-check-result").assertDoesNotExist()
    }

    @Test fun a_failed_time_check_does_not_hide_the_saved_server() {
        screen(connected.copy(currentNtpServer = "pool.ntp.org",
            ntpMessage = UiMessage(com.civisrom.tvtimefixer.R.string.ntp_applied, listOf("pool.ntp.org")),
            timeCheck = DeviceTimeCheck(DeviceTimeStatus.NTP_UNAVAILABLE, server = "pool.ntp.org")))
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.ntp_applied, "pool.ntp.org"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-check-status").performScrollTo()
            .assertTextContains(russianString(com.civisrom.tvtimefixer.R.string.time_check_ntp_unavailable))
    }

    @Test fun time_result_and_disabled_automatic_time_are_readable_at_large_font() {
        screen(connected.copy(timeCheck = DeviceTimeCheck(DeviceTimeStatus.MATCH, server = "pool.ntp.org",
            deviceTimeMillis = 1_800_000_000_000, differenceSeconds = 0.2, uncertaintySeconds = 0.6,
            automaticTime = false)), mode = DeviceMode.TELEVISION, scale = 2f, width = 320)
        compose.onNodeWithTag("time-check-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.time_check_auto_off))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-check").performScrollTo().performClick()
        assertEquals(listOf("verify-time"), actions.calls)
    }

    @Test fun checked_time_shows_device_local_time_and_UTC_for_the_same_instant() {
        screen(connected.copy(timeCheck = DeviceTimeCheck(DeviceTimeStatus.MATCH,
            deviceTimeMillis = 1_788_873_348_000L, timeZoneId = "Europe/Moscow")))
        compose.onNodeWithText("Местное время устройства при проверке: 2026-09-08 16:15:48 (Europe/Moscow)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Время устройства при проверке (UTC): 2026-09-08 13:15:48")
            .performScrollTo().assertIsDisplayed()
        screenshot("time-check-local-and-utc")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun time_zone_menu_is_visible_without_connection_and_starts_collapsed() {
        screen()
        compose.onNodeWithTag("section-timezone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-zone-search").assertDoesNotExist()
        compose.onNodeWithTag("section-timezone").performClick()
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.time_zone_connect_first))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("time-zone-apply").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun time_zone_selection_survives_collapsing_and_only_explicit_apply_changes_settings() {
        screen(connected.copy(deviceInfo = DeviceInfo(timezone = "UTC")), mode = DeviceMode.TELEVISION)
        compose.onNodeWithTag("section-timezone").performScrollTo().performClick()
        compose.onNodeWithTag("time-zone-search").performScrollTo().performTextInput("Europe/Moscow")
        waitForKeyboard()
        compose.onNodeWithTag("time-zone-option-Europe/Moscow").performScrollTo().performClick()
        compose.onNodeWithTag("time-zone-apply").assertIsDisplayed().assertIsFocused()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        screenshot("time-zone-selected")
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("section-timezone").performScrollTo().performClick()
        compose.onNodeWithTag("time-zone-search").assertDoesNotExist()
        compose.onNodeWithTag("section-timezone").performClick()
        compose.onNodeWithTag("time-zone-search").performScrollTo().assertTextContains("Europe/Moscow")
        compose.onNodeWithTag("time-zone-apply").performScrollTo().performClick()
        assertEquals(listOf("zone:Europe/Moscow"), actions.calls)
    }

    @Test fun time_zone_changes_are_disabled_while_an_operation_is_running() {
        screen(connected.copy(busy = true, operation = Operation.APPLY_TIME_ZONE))
        compose.onNodeWithTag("section-timezone").performScrollTo().performClick()
        compose.onNodeWithTag("time-zone-search").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time-zone-apply").performScrollTo().assertIsNotEnabled()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun unconfirmed_time_zone_restoration_is_visible_at_large_font() {
        screen(connected.copy(timeZoneResult = TimeZoneUpdateResult.Failed(TimeZoneFailure.WRITE,
            TimeZoneRestoration.UNCONFIRMED)), scale = 2f, width = 320)
        compose.onNodeWithTag("section-timezone").performScrollTo().performClick()
        compose.onNodeWithText(russianString(com.civisrom.tvtimefixer.R.string.time_zone_restore_failed))
            .performScrollTo().assertIsDisplayed()
        screenshot("time-zone-restoration-warning")
        assertTrue(actions.calls.isEmpty())
    }

    private fun screen(state: AppState = AppState(), mode: DeviceMode = DeviceMode.HANDHELD,
        scale: Float = 1f, width: Int = 360, diagnostics: DiagnosticSnapshot = DiagnosticSnapshot(),
        clear: () -> Unit = {},
    ) {
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            hostView = LocalView.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag("ru")) }
            val localized = context.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme {
                    Surface {
                        Box(Modifier.fillMaxSize().requiredWidth(width.dp)) {
                            MainScreen(mode, state, actions, diagnostics, onClearDiagnostics = clear)
                        }
                    }
                }
            }
        }
    }

    @Test fun opening_diagnostics_preserves_network_input_and_does_not_connect() {
        screen()
        compose.onNodeWithTag("network-address").performScrollTo().performTextInput("192.168.1.10:5555")
        // Проверяем сохранение формы; появление IME не должно сместить тестовое касание.
        compose.onNodeWithTag("diagnostics-open").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("network-address").performScrollTo().assertTextContains("192.168.1.10:5555")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun primary_forms_are_visible_without_expanding_menus() {
        screen()
        for (tag in listOf("network-address", "ntp-address")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
        compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
        compose.onNodeWithTag("ntp-check").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("check:pool.ntp.org"), actions.calls)
        compose.onNodeWithTag("network-address").performScrollTo()
        screenshot("primary-connection")
    }

    @Test fun pairing_collapses_without_losing_inputs_or_running_actions() {
        screen()
        compose.onNodeWithTag("pairing-code").assertDoesNotExist()
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-address").performScrollTo().performTextInput("192.0.2.10:37123")
        compose.onNodeWithTag("pairing-code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("pairing-connect-address").performScrollTo().performTextInput("192.0.2.10:37124")
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-code").assertDoesNotExist()
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-address").assertTextContains("192.0.2.10:37123")
        compose.onNodeWithTag("pairing-code").assertTextContains("123456")
        compose.onNodeWithTag("pairing-connect-address").assertTextContains("192.0.2.10:37124")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun connecting_keeps_primary_inputs_and_enables_ntp_writes_only_while_connected() {
        val state = mutableStateOf(AppState(ntpCheck = NtpProbeResult("pool.ntp.org", false, 0, null, null, "timeout")))
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, state.value, actions) } }
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("network-address").performScrollTo().performTextInput("192.0.2.10:5555")
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
        compose.onNodeWithTag("pairing-address").performScrollTo().performTextInput("192.0.2.11:37123")
        compose.onNodeWithTag("pairing-connect-address").performScrollTo().performTextInput("192.0.2.11:37124")
        compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
        compose.onNodeWithTag("ntp-apply-anyway").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(connection = connected.connection) }
        compose.onNodeWithTag("network-address").performScrollTo().assertTextContains("192.0.2.10:5555")
        compose.onNodeWithTag("pairing-address").performScrollTo().assertTextContains("192.0.2.11:37123")
        compose.onNodeWithTag("pairing-connect-address").performScrollTo().assertTextContains("192.0.2.11:37124")
        compose.onNodeWithTag("ntp-address").performScrollTo().assertTextContains("pool.ntp.org")
        compose.onNodeWithTag("ntp-apply-anyway").assertDoesNotExist()
        compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("apply:pool.ntp.org"), actions.calls)

        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
        compose.onNodeWithTag("ntp-apply-anyway").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionState.Disconnected, busy = false) }
        compose.onNodeWithTag("ntp-address").assertTextContains("pool.ntp.org")
        compose.onNodeWithTag("ntp-apply").assertIsNotEnabled()
        compose.onNodeWithTag("ntp-apply-anyway").assertDoesNotExist()
        assertEquals(listOf("apply:pool.ntp.org"), actions.calls)
    }

    @Test fun narrow_screen_at_double_font_keeps_pairing_fields_and_action_reachable() {
        screen(scale = 2f, width = 320)
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-address").performScrollTo().performTextInput("192.0.2.10:37123")
        compose.onNodeWithTag("pairing-code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("pairing-connect-address").performScrollTo().performTextInput("192.0.2.10:37124")
        waitForKeyboard()
        compose.onNodeWithTag("pairing-connect").performScrollTo().assertIsDisplayed()
        screenshot("phone-320-font200-pairing")
        assertTrue(actions.calls.isEmpty())
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            compose.onNodeWithTag("pairing-connect").assertIsEnabled().performClick()
            assertEquals(listOf("pair:192.0.2.10:37123:123456:192.0.2.10:37124"), actions.calls)
        } else {
            compose.onNodeWithTag("pairing-connect").assertIsNotEnabled()
        }
    }

    @Test fun discovered_device_expands_results_without_hiding_primary_forms() {
        val state = mutableStateOf(AppState())
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, state.value, actions) } }
        compose.runOnIdle {
            state.value = state.value.copy(discovered = listOf(DiscoveredDevice("Living room TV",
                DeviceAddress("192.0.2.10", 37123), DiscoveredDevice.Kind.READY_TO_CONNECT)))
        }
        compose.onNodeWithText("Living room TV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("section-discovery").performScrollTo().performClick()
        compose.onNodeWithText("Living room TV").assertDoesNotExist()
        compose.onNodeWithTag("section-pairing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pairing-code").assertDoesNotExist()
        compose.onNodeWithTag("ntp-address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("network-address").performScrollTo().assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun ntp_input_survives_diagnostics_and_only_explicit_apply_executes() {
        screen(connected)
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
        // IME changes the viewport; wait before scrolling and injecting a toolbar tap.
        waitForKeyboard()
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("ntp-address").performScrollTo().assertTextContains("pool.ntp.org")
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("ntp-apply").performScrollTo().performClick()
        assertEquals(listOf("apply:pool.ntp.org"), actions.calls)
    }

    @Test fun busy_usb_permission_keeps_diagnostics_accessible() {
        screen(AppState(busy = true, operation = Operation.USB_PERMISSION,
            connection = ConnectionState.Connecting(UsbDeviceAddress("/dev/bus/usb/test", "TV"))))
        compose.onNodeWithTag("diagnostics-open").assertIsEnabled().performClick()
        compose.onNodeWithTag("diagnostics-back").performClick()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun collapsed_picker_keeps_scan_progress_visible() {
        screen(connected.copy(ntpScan = ScanProgress(3, 20, emptyList())))
        compose.onNodeWithTag("ntp-scan-progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-scan-progress").performScrollTo().assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun starting_and_restarting_a_scan_shows_progress_at_the_button() {
        val state = mutableStateOf(connected)
        actions.onScan = { state.value = state.value.copy(ntpScan = ScanProgress(0, 20, emptyList())) }
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, state.value, actions) } }
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        repeat(2) {
            compose.onNodeWithTag("ntp-countries").performScrollTo().performClick()
            compose.onNodeWithTag("ntp-alternatives").performScrollTo().performClick()
            compose.onNodeWithText("VN ·", substring = true).assertExists()
            compose.onNodeWithText("time.cloudflare.com").assertExists()
            compose.onNodeWithTag("ntp-scan-start").performScrollTo().performClick()
            compose.onNodeWithText("VN ·", substring = true).assertDoesNotExist()
            compose.onNodeWithText("time.cloudflare.com").assertDoesNotExist()
            compose.onNodeWithTag("ntp-scan-progress").assertIsDisplayed().assertTextContains(
                context.getString(com.civisrom.tvtimefixer.R.string.ntp_scan_progress, 0, 20, 0))
            compose.onNodeWithTag("ntp-scan-cancel").assertIsDisplayed()
            compose.onNodeWithTag("ntp-scan-start").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(ntpScan = ScanProgress(20, 20, emptyList())) }
            compose.onNodeWithTag("ntp-scan-progress").assertDoesNotExist()
        }
        compose.onNodeWithTag("ntp-scan-start").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-scan-cancel").performClick()
        assertEquals(listOf("scan", "scan", "scan", "cancel-scan"), actions.calls)
    }

    @Test fun picking_a_country_reveals_the_address_and_actions_even_when_picked_again() {
        screen(connected)
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        repeat(2) {
            compose.onNodeWithTag("ntp-countries").performScrollTo().performClick()
            compose.onNodeWithText("VN ·", substring = true).performScrollTo().performClick()
            compose.onNodeWithTag("ntp-address").assertIsDisplayed().assertTextContains("vn.pool.ntp.org")
            compose.onNodeWithTag("ntp-apply").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("ntp-check").assertIsDisplayed()
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun picking_an_alternative_reveals_the_address_before_applying() {
        screen(connected)
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-alternatives").performScrollTo().performClick()
        compose.onNodeWithText("time.cloudflare.com").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-address").assertIsDisplayed().assertTextContains("time.cloudflare.com")
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("ntp-apply").assertIsDisplayed().performClick()
        assertEquals(listOf("apply:time.cloudflare.com"), actions.calls)
    }

    @Test fun picking_a_search_result_hides_the_keyboard_and_reveals_the_address() {
        assertSearchSelection(connected)
    }

    @Test fun picking_a_search_result_without_a_connection_hides_the_keyboard() {
        assertSearchSelection(AppState())
    }

    private fun assertSearchSelection(state: AppState) {
        screen(state)
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-search").performScrollTo().performTextInput("cloudflare")
        compose.onNodeWithTag("ntp-search").performTouchInput { click() }
        waitForKeyboard()
        compose.onNodeWithText("time.cloudflare.com").performScrollTo().performClick()
        try {
            compose.onNodeWithTag("ntp-address").assertTextContains("time.cloudflare.com").assertIsNotFocused()
            compose.onNodeWithTag("ntp-search").assertIsNotFocused()
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            compose.onNodeWithTag("ntp-address").assertIsDisplayed()
            compose.onNodeWithTag("ntp-apply").assertIsDisplayed().also {
                if (state.connected) it.assertIsEnabled() else it.assertIsNotEnabled()
            }
            compose.onNodeWithTag("ntp-check").assertIsDisplayed().assertIsFocused()
        } finally {
            screenshot(if (state.connected) "search-selection" else "search-selection-disconnected")
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun picking_a_scan_result_or_its_ip_reveals_the_address_and_clears_results() {
        val result = NtpProbeResult("time.example.org", true, 100, 10, 0.1, null, "192.0.2.123")
        val results = listOf(result) + (1..4).map { result.copy(server = "time$it.example.org", ipAddress = null) }
        val scan = ScanProgress(20, 20, results)
        val state = mutableStateOf(connected.copy(ntpScan = scan))
        actions.onClearScan = { state.value = state.value.copy(ntpScan = null) }
        compose.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, state.value, actions) } }
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        for (address in listOf(result.server, checkNotNull(result.ipAddress))) {
            compose.runOnIdle { state.value = state.value.copy(ntpScan = scan) }
            val label = if (address == result.server) "$address —" else address
            compose.onNodeWithText(label, substring = true).performScrollTo().performClick()
            compose.onNodeWithTag("ntp-address").assertIsDisplayed().assertTextContains(address)
            compose.onNodeWithTag("ntp-check").assertIsDisplayed()
            for (entry in results) {
                compose.onNodeWithText("${entry.server} —", substring = true).assertDoesNotExist()
            }
            compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
            compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
            compose.onNodeWithText("${result.server} —", substring = true).assertDoesNotExist()
        }
        assertEquals(listOf("clear-scan", "clear-scan"), actions.calls)
    }

    @Test fun diagnostics_clear_requires_confirmation_and_preserves_connection() {
        val history = DiagnosticSnapshot(listOf(DiagnosticEvent(1, System.currentTimeMillis(),
            Operation.CONNECT_USB, Outcome.FAILED, reason = ConnectionError.USB_PERMISSION_DENIED)))
        var clears = 0
        screen(connected, diagnostics = history, clear = { clears++ })
        compose.onNodeWithTag("diagnostics-open").performClick()
        compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
        assertEquals(0, clears)
        compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
        assertEquals(1, clears)
        compose.onNodeWithTag("diagnostics-back").performClick()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun remote_control_can_open_diagnostics_and_focus_returns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            screen(mode = DeviceMode.TELEVISION)
            // Переключаем уже созданный Compose view, как в тестах AndroidX.
            compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
            compose.onNodeWithTag("diagnostics-open").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("diagnostics-open").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithTag("diagnostics-back").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithTag("diagnostics-open").assertIsFocused()
            assertTrue(actions.calls.isEmpty())
            screenshot("tv-focus")
        } finally {
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun narrow_screen_at_double_font_keeps_ntp_actions_and_diagnostics_reachable() {
        screen(connected, scale = 2f, width = 320)
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("time.example.org")
        waitForKeyboard()
        compose.onNodeWithTag("ntp-check").performScrollTo().assertIsDisplayed()
        screenshot("phone-320-font200-ntp")
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-back").assertIsDisplayed()
        screenshot("phone-320-font200-diagnostics")
    }

    @Test fun recreated_ui_restores_addresses_without_replaying_actions() {
        val restoration = StateRestorationTester(compose)
        val state = mutableStateOf(AppState())
        restoration.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, state.value, actions) } }
        compose.onNodeWithTag("network-address").performScrollTo().performTextInput("192.168.1.9:5555")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("network-address").performScrollTo().assertTextContains("192.168.1.9:5555")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun discovered_device_identifies_the_pairing_target_without_connecting() {
        screen(AppState(discovered = listOf(DiscoveredDevice("Android TV в гостиной",
            DeviceAddress("192.0.2.10", 37123), DiscoveredDevice.Kind.AWAITING_PAIRING))))
        compose.onNodeWithText("192.0.2.10:37123", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Найдено устройство").assertIsDisplayed()
        compose.onNodeWithText("Android TV в гостиной").assertIsDisplayed()
        screenshot("network-device-found")
        compose.onNodeWithText("Подключено к этому устройству").assertDoesNotExist()
        compose.onNodeWithText("Спарить").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-code").performScrollTo().assertIsFocused()
        compose.onNodeWithTag("pairing-address").performScrollTo().assertTextContains("192.0.2.10:37123")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun a_discovered_address_can_be_copied_with_its_port_and_pasted_into_input_fields() {
        val address = "192.0.2.10:37123"
        screen(AppState(discovered = listOf(DiscoveredDevice("Living room TV",
            DeviceAddress("192.0.2.10", 37123), DiscoveredDevice.Kind.AWAITING_PAIRING))))
        copyDisplayedText(address)
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        for (tag in listOf("network-address", "pairing-address", "pairing-connect-address", "ntp-address")) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            waitForKeyboard()
            compose.onNodeWithTag(tag).performScrollTo().assertIsFocused()
            try {
                compose.onNodeWithTag(tag).performTouchInput { longClick(center) }
                clickTextSelectionAction(android.R.string.paste)
                // Проверяем результат единственной вставки через системное меню.
                compose.waitUntil(5_000) {
                    compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == address
                }
            } finally {
                screenshot("paste-$tag")
            }
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun long_pressing_an_ntp_server_copies_it_without_selecting_or_applying_it() {
        val server = "time.cloudflare.com"
        screen(connected)
        compose.onNodeWithTag("section-ntp-picker").performScrollTo().performClick()
        compose.onNodeWithTag("ntp-alternatives").performScrollTo().performClick()
        copyDisplayedText(server)
        compose.onNodeWithTag("ntp-address").assert(hasText(server).not())
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithText(server, useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("ntp-address").assertTextContains(server)
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun pairing_code_survives_diagnostics_but_not_saved_state_restore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { MainScreen(DeviceMode.HANDHELD, AppState(), actions) } }
        compose.onNodeWithTag("section-pairing").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("pairing-code").performScrollTo().assertTextContains("123456")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pairing-code").performScrollTo().assert(hasText("123456").not())
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun error_details_open_the_matching_record() {
        val history = DiagnosticSnapshot((1L..30L).map { id -> DiagnosticEvent(id, System.currentTimeMillis(),
            Operation.CONNECT_USB, if (id == 2L) Outcome.FAILED else Outcome.SUCCESS) })
        screen(AppState(connection = ConnectionState.Failed(null, ConnectionError.USB_IO), diagnosticEventId = 2),
            diagnostics = history)
        compose.onNodeWithTag("connection-details").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostic-2").assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun usb_without_adb_is_distinguishable_and_refresh_does_not_connect() {
        screen(AppState(usbSupported = true, usbAttachedCount = 1))
        compose.onNodeWithTag("usb-refresh").performScrollTo().performClick()
        assertEquals(listOf("usb-list"), actions.calls)
        compose.onNodeWithText("Android видит USB-устройство", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("usb-without-adb")
    }

    @Test fun empty_usb_search_does_not_claim_successful_connection() {
        val history = DiagnosticSnapshot(listOf(DiagnosticEvent(1, System.currentTimeMillis(),
            Operation.USB_SCAN, Outcome.SUCCESS, issue = DiagnosticIssue.USB_NONE)))
        screen(AppState(usbSupported = true), diagnostics = history)
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithText("Поиск USB-устройств — USB-устройства не обнаружены")
            .performScrollTo().assertIsDisplayed()
        val report = diagnosticReport(context, history, DeviceMode.HANDHELD)
        assertFalse(report.contains(context.getString(com.civisrom.tvtimefixer.R.string.diagnostics_success)))
        assertTrue(report.contains(context.getString(com.civisrom.tvtimefixer.R.string.diagnostics_usb_none)))
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun usb_scan_uses_the_same_unfiltered_device_count_as_android() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbDevices(context).use { usb ->
            val rawCount = manager.deviceList.size
            val listing = usb.scan()
            assertEquals(rawCount, listing.attachedCount)
            assertEquals(rawCount, usb.observation(listing).attachedCount)
            assertTrue(listing.devices.size <= rawCount)
        }
    }

    @Test fun usb_system_state_does_not_turn_missing_flags_into_host_disabled() {
        assertEquals(UsbSystemState(), usbSystemState(null))
        assertEquals(UsbSystemState(), usbSystemState(Intent("unrelated")))
        assertEquals(UsbSystemState(), usbSystemState(Intent(ACTION_USB_SYSTEM_STATE)))
        val device = Intent(ACTION_USB_SYSTEM_STATE).putExtra("host_connected", false)
            .putExtra("connected", true).putExtra("configured", true)
        assertEquals(UsbSystemState(false, true, true), usbSystemState(device))
        val host = Intent(ACTION_USB_SYSTEM_STATE).putExtra("host_connected", true)
        assertEquals(UsbSystemState(true, null, null), usbSystemState(host))

        screen(AppState(usbSupported = true, usbSystemState = usbSystemState(device)))
        compose.onNodeWithTag("section-usb").performScrollTo().performClick()
        compose.onNodeWithTag("section-usb-help").performScrollTo().performClick()
        compose.onNodeWithText("Устройство с этим приложением должно работать USB-хостом (OTG)", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun landscape_keeps_the_main_actions_reachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            screen(connected, width = 640)
            compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("time.example.org")
            waitForKeyboard()
            compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsDisplayed()
            screenshot("landscape-ntp")
            compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
            try {
                compose.waitUntil(5_000) {
                    ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
                }
                compose.onNodeWithTag("diagnostics-back").assertIsDisplayed()
            } finally {
                screenshot("landscape-diagnostics")
            }
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            compose.waitUntil(10_000) { context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun android_can_offer_this_app_for_usb_attachment() {
        val matches = context.packageManager.queryIntentActivities(
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).setPackage(context.packageName), 0)
        assertTrue(matches.any { it.activityInfo.name == "com.civisrom.tvtimefixer.MainActivity" })
        assertTrue(actions.calls.isEmpty())
    }

    private fun copyDisplayedText(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
        val content = compose.onNodeWithTag("main-content")
        // Оставляем место для маркеров выделения и системного меню, вдали от панели навигации.
        val scroll = node.fetchSemanticsNode().boundsInRoot.center.y - content.fetchSemanticsNode().boundsInRoot.center.y
        content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll) }
        node.performTouchInput {
            longClick(Offset(5f, center.y))
        }
        try {
            compose.waitUntil(5_000) { textSelectionActions(android.R.string.copy).isNotEmpty() }
            screenshot("copy-menu-${text.substringBefore(':')}")
            // Домен может сразу выделиться целиком; тогда Android не предлагает «Выделить всё».
            if (textSelectionActions(android.R.string.selectAll).isNotEmpty()) {
                clickTextSelectionAction(android.R.string.selectAll)
            }
        } finally {
            screenshot("copy-${text.substringBefore(':')}")
        }
        clickTextSelectionAction(android.R.string.copy)
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    private fun clickTextSelectionAction(label: Int) {
        // Дожидаемся доступного действия, затем нажимаем его ровно один раз.
        // Повторные ACTION_CLICK во время перестройки панели могут убрать выделение.
        var action: AccessibilityNodeInfo? = null
        compose.waitUntil(5_000) {
            action = textSelectionActions(label).firstOrNull { it.isClickable && it.isEnabled }
            action != null
        }
        assertTrue(checkNotNull(action).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
    }

    private fun textSelectionActions(label: Int): List<AccessibilityNodeInfo> =
        // Меню использует русскую локаль Compose и может находиться в отдельном окне.
        InstrumentationRegistry.getInstrumentation().uiAutomation.windows
            // Ищем меню приложения, а не предложения IME. Поиск текста внутри
            // клавиатуры AOSP API 23 роняет её AccessibilityNodeProviderCompat.
            .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByText(russianString(label)) }

    private fun waitForKeyboard() {
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(bitmap)
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
