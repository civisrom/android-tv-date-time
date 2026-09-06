package com.civisrom.tvtimefixer.ui

import android.app.UiAutomation
import android.content.Intent
import android.hardware.usb.UsbManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.DeviceMode
import com.civisrom.tvtimefixer.adb.ConnectionError
import com.civisrom.tvtimefixer.adb.ConnectionState
import com.civisrom.tvtimefixer.adb.UsbDeviceAddress
import com.civisrom.tvtimefixer.adb.UsbDevices
import com.civisrom.tvtimefixer.adb.ACTION_USB_SYSTEM_STATE
import com.civisrom.tvtimefixer.adb.usbSystemState
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.data.ScanProgress
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

private class ScreenActions : AppActions {
    val calls = mutableListOf<String>()
    override fun connect(address: String) { calls += "connect:$address" }
    override fun connectLoopback() { calls += "loopback" }
    override fun disconnect() { calls += "disconnect" }
    override fun pairAndConnect(pairingAddress: String, code: String, connectAddress: String) { calls += "pair" }
    override fun checkNtpServer(server: String) { calls += "check:$server" }
    override fun applyNtpServer(server: String, force: Boolean) { calls += "apply:$server:$force" }
    override fun scanNtpServers() { calls += "scan" }
    override fun cancelNtpScan() { calls += "cancel-scan" }
    override fun refreshDeviceInfo() { calls += "info" }
    override fun requestDiscoveryPermission() { calls += "permission" }
    override fun refreshUsbDevices() { calls += "usb-list" }
    override fun connectUsb(address: UsbDeviceAddress) { calls += "usb-connect" }
}

class MainScreenTest {
    @get:Rule val compose = createComposeRule()
    private val actions = ScreenActions()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connected = AppState(connection = ConnectionState.Connected(DeviceAddress("192.168.1.2", 5555)))

    private fun screen(state: AppState = AppState(), mode: DeviceMode = DeviceMode.HANDHELD,
        scale: Float = 1f, width: Int = 360, diagnostics: DiagnosticSnapshot = DiagnosticSnapshot(),
        clear: () -> Unit = {},
    ) {
        compose.setContent {
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
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("network-address").performScrollTo().assertTextContains("192.168.1.10:5555")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun ntp_input_survives_diagnostics_and_only_explicit_apply_executes() {
        screen(connected)
        compose.onNodeWithTag("ntp-address").performScrollTo().performTextInput("pool.ntp.org")
        compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-back").performClick()
        compose.onNodeWithTag("ntp-address").performScrollTo().assertTextContains("pool.ntp.org")
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("ntp-apply").performScrollTo().performClick()
        assertEquals(listOf("apply:pool.ntp.org:false"), actions.calls)
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
        instrumentation.setInTouchMode(false)
        try {
            screen(mode = DeviceMode.TELEVISION)
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
        compose.onNodeWithText("Система сообщает подключение в режиме USB-устройства", substring = true)
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
            compose.onNodeWithTag("ntp-apply").performScrollTo().assertIsDisplayed()
            screenshot("landscape-ntp")
            compose.onNodeWithTag("diagnostics-open").performScrollTo().performClick()
            compose.onNodeWithTag("diagnostics-back").assertIsDisplayed()
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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val folder = File(context.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(bitmap)
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
