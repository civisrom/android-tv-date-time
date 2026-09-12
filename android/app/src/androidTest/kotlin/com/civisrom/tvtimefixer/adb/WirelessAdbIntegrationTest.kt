package com.civisrom.tvtimefixer.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.civisrom.tvtimefixer.terminal.*
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Opt-in only: changes debugging settings on the disposable CI emulator. */
@android.annotation.TargetApi(30)
class WirelessAdbIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun real_pairing_reconnect_and_terminal_file_and_APK_operations() = runBlocking {
        assumeTrue("Requires the dedicated wireless CI step",
            InstrumentationRegistry.getArguments().getString("real_wireless_pairing") == "true")
        check(Build.VERSION.SDK_INT >= 30)
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Only disposable emulators are supported" }

        // Same system service as Settings/CTS. These exemptions and shell permissions
        // belong to test setup only; the production client still uses its normal identity.
        check(HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;", "Landroid/debug/"))
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "adb") as IBinder
        val manager = Class.forName("android.debug.IAdbManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val managerType = Class.forName("android.debug.IAdbManager")
        fun manage(name: String, vararg args: Any): Any? = privileged {
            val method = managerType.methods.single { it.name == name && it.parameterCount == args.size }
            method.invoke(manager, *args)
        }
        val events = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { events.offer(intent) }
        }
        val filter = IntentFilter("com.android.server.adb.WIRELESS_DEBUG_PAIRING_RESULT")
        privileged {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        }
        val previousWireless = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
        val connector = DeviceConnector(KadbAdbClientFactory(5_000, 8_000))
        val files = TerminalFiles(File(context.cacheDir, "real-wireless-terminal"))
        val remote = "/data/local/tmp/tvtimefixer-wireless-test.bin"
        val packageName = "com.civisrom.tvtimefixer.terminalfixture"
        var connectionPort = 0
        fun beginPairing(): Pair<DeviceAddress, String> = privileged {
            events.clear()
            managerType.getMethod("enablePairingByPairingCode").invoke(manager)
            var code: String? = null
            var port = 0
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (code == null || port == 0) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                check(remaining > 0) { "System pairing code/port was not delivered" }
                val event = checkNotNull(events.poll(remaining, TimeUnit.MILLISECONDS)) { "No system pairing event" }
                when (event.getIntExtra("status", -1)) {
                    3 -> code = event.getStringExtra("pairing_code")
                    4 -> port = event.getIntExtra("adb_port", 0)
                    0 -> error("System pairing service failed to start")
                }
            }
            val pairingCode = checkNotNull(code)
            check(pairingCode.matches(Regex("[0-9]{6}")) && port in 1..65535)
            check(port != connectionPort) { "Pairing and connection must use distinct ports" }
            DeviceAddress("127.0.0.1", port) to pairingCode
        }
        try {
            assertEquals(true, manage("isAdbWifiSupported"))
            shell("svc wifi enable")
            var bssid: String? = null
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            waitFor("Emulator Wi-Fi did not connect") {
                @Suppress("DEPRECATION")
                bssid = privileged { wifi.connectionInfo.bssid }
                bssid != null && bssid != "02:00:00:00:00:00"
            }
            manage("allowWirelessDebugging", false, checkNotNull(bssid))
            waitFor("Wireless ADB connection port was not opened") {
                connectionPort = manage("getAdbWirelessPort") as Int
                connectionPort in 1..65535
            }
            val address = DeviceAddress("127.0.0.1", connectionPort)
            val factory = KadbAdbClientFactory(5_000, 8_000)
            val unpaired = runCatching { factory.connect(address).close() }.exceptionOrNull()
            assertTrue("Unpaired ADB clients must be rejected", unpaired is AdbConnectionException)

            val (wrongEndpoint, actualCode) = beginPairing()
            val wrongCode = (if (actualCode[0] == '0') "1" else "0") + actualCode.drop(1)
            val rejected = runCatching { factory.pair(wrongEndpoint, wrongCode) }.exceptionOrNull()
            assertTrue("Wrong pairing code was accepted", rejected is AdbConnectionException)
            assertTrue("Wrong code must fail authentication/protocol exchange",
                (rejected as AdbConnectionException).reason in setOf(ConnectionError.PAIRING_REJECTED, ConnectionError.PAIRING_FAILED))
            manage("disablePairing")

            val (endpoint, code) = beginPairing()
            assertEquals(ConnectionState.Connected(address), connector.pairAndConnect(endpoint.toString(), code, address.toString()))
            connector.disconnect()
            assertEquals(ConnectionState.Connected(address), connector.connect(address))
            val client = checkNotNull(connector.activeClient)
            val session = TerminalSession()
            fun execute(command: String, expected: Int = 0) {
                session.edit(command)
                val parsed = checkNotNull(session.start(address.toString()))
                session.finish(TerminalExecutor(files, session).execute(client, parsed))
                assertEquals(session.state.value.output.joinToString("") { it.text }, expected, session.state.value.exitCode)
            }
            execute("echo wireless-output; echo wireless-error >&2; exit 7", 7)
            assertTrue(session.state.value.output.any { !it.error && "wireless-output" in it.text })
            assertTrue(session.state.value.output.any { it.error && "wireless-error" in it.text })
            val bytes = ByteArray(80_003) { (it % 251).toByte() }
            files.receive("payload.bin") { it.write(bytes) }
            execute("adb push payload.bin $remote")
            assertEquals(bytes.size.toLong(), session.state.value.transferred)
            execute("adb pull $remote received.bin")
            assertArrayEquals(bytes, files.resolve("received.bin").readBytes())
            for ((asset, name) in listOf("terminal-fixture.apk" to "base.apk", "terminal-fixture-split.apk" to "split.apk")) {
                instrumentation.context.assets.open(asset).use { input -> files.receive(name) { input.copyTo(it) } }
            }
            execute("adb install -t base.apk")
            execute("adb install -r -t base.apk")
            execute("adb install-multiple -r -t base.apk split.apk")
            assertEquals(files.resolve("base.apk").length() + files.resolve("split.apk").length(), session.state.value.transferred)
            assertEquals(2, client.shell("pm path $packageName").output.lineSequence().count { it.startsWith("package:") })
            execute("adb uninstall $packageName")
            assertFalse(client.shell("pm path $packageName").output.startsWith("package:"))
        } finally {
            connector.disconnect()
            runCatching { manage("disablePairing") }
            privileged { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", previousWireless) }
            context.unregisterReceiver(receiver)
            shell("rm -f $remote")
            shell("pm uninstall $packageName")
            files.directory.deleteRecursively()
        }
    }

    private inline fun <T> privileged(block: () -> T): T {
        automation.adoptShellPermissionIdentity()
        try { return block() } finally { automation.dropShellPermissionIdentity() }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun waitFor(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!ready()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            Thread.sleep(200)
        }
    }
}
