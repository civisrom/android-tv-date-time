package com.civisrom.tvtimefixer.adb

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.data.*
import com.civisrom.tvtimefixer.device.*
import com.civisrom.tvtimefixer.diagnostics.*
import com.civisrom.tvtimefixer.net.UdpSntpClient
import com.civisrom.tvtimefixer.terminal.shellQuote
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opt-in. Loopback NTP fixtures must serve the real host UTC; never set synthetic system time. */
class TimeFeaturesIntegrationTest {
    @Test fun real_persistent_restore_profiles_IPv6_fallback_monitor_and_redacted_export() {
        val args = InstrumentationRegistry.getArguments()
        val input = args.getString("real_time_features_address")
        assumeTrue("Requires disposable target and real-time NTP fixtures", args.getString("real_time_features") == "1" && input != null)
        val address = checkNotNull(parseDeviceAddress(checkNotNull(input)))
        val connector = DeviceConnector(KadbAdbClientFactory(5_000, 8_000))
        assertEquals(ConnectionState.Connected(address), connector.connect(address))
        val client = checkNotNull(connector.activeClient)
        assertEquals("2000", client.shell("id -u").trimmedOutput)
        val repository = TimeSettingsRepository(client)
        val original = repository.capture()
        assertTrue("All original values and stable identity are required before any write", original.capturable)
        val identity = checkNotNull(original.identity)
        assertTrue((original.apiLevel ?: 0) >= 34)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "time-features-${SystemClock.elapsedRealtime()}.bin")
        try {
            var store = TimeSettingsStore(file)
            val baseline = SavedTimeSettings(identity, System.currentTimeMillis(), original.settings)
            store.saveSnapshot(baseline)
            store = TimeSettingsStore(file) // Recreate the owner, as after a process restart.
            assertEquals(baseline, store.read().snapshots.single())
            assertTrue(repository.apply(identity, TimeSettings(mapOf(TimeSetting.AUTO_TIME to "0"))).confirmed)
            assertEquals("0", client.shell("settings get global auto_time").trimmedOutput)

            val fallbackHosts = listOf("127.0.0.2", "::1")
            val changed = repository.applyNtpHosts(identity, fallbackHosts)
            assertTrue(changed.toString(), changed.confirmed)
            val configured = repository.capture()
            assertTrue(configured.capturable)
            assertEquals("ntp://127.0.0.2|ntp://[::1]", configured.settings[TimeSetting.NTP])
            val check = DeviceTimeVerifier(UdpSntpClient(500), SystemClock::elapsedRealtime).verify(client)
            assertEquals(DeviceTimeStatus.MATCH, check.status)
            assertEquals("::1", check.server)
            assertTrue(check.isFresh(SystemClock.elapsedRealtime()))
            assertEquals(original.settings[TimeSetting.TIME_ZONE], repository.read().settings[TimeSetting.TIME_ZONE])
            println("Time features: persisted identity-bound baseline, mixed IPv4/IPv6 setting and real UDP fallback PASS")

            // Android's own service refresh is safe only while auto_time is explicitly disabled.
            assertEquals("0", client.shell("settings get global auto_time").trimmedOutput)
            val refreshed = client.shell("cmd network_time_update_service force_refresh")
            assertFalse(refreshed.permissionDenied)
            assertEquals(0, refreshed.exitCode)
            val dump = client.shell("dumpsys network_time_update_service")
            assertTrue(dump.output, "ntp://[::1]" in dump.output)
            val evidence = TimeSourceReader(client).read()
            assertEquals(TimeSourceReadStatus.AVAILABLE, evidence.networkStatus)
            assertEquals("ntp://[::1]", evidence.lastResponseUri)
            println("Time features: Android service IPv6 fallback confirmed separately from clock-source claims")

            val profile = TimeProfile("IPv6 fallback", SavedTimeSettings(identity, System.currentTimeMillis(), configured.settings))
            store.saveProfile(profile)
            val beforePreview = repository.read().settings
            assertEquals(profile, TimeSettingsStore(file).read().profiles.single()) // Preview / cancel is read only.
            assertEquals(beforePreview, repository.read().settings)
            assertTrue(repository.applyNtpHosts(identity, listOf("127.0.0.1")).confirmed)
            val restoredProfile = TimeSettingsStore(file).read().profiles.single()
            assertTrue(repository.apply(identity, restoredProfile.saved.settings, rollbackOnFailure = true).confirmed)
            assertEquals(configured.settings, repository.read().settings)
            store.removeProfile(identity, profile.name)
            assertTrue(TimeSettingsStore(file).read().profiles.isEmpty())

            // Denied / ignored writes use the live device for reads but never send the rejected write.
            fun guarded(ignoreWrite: Boolean): AdbClient = object : AdbClient by client {
                override fun shell(command: String): ShellResult = if (command.startsWith("settings put global ntp_server")) {
                    if (ignoreWrite) ShellResult("", "", 0) else ShellResult("", "Permission denied", 1)
                } else client.shell(command)
                override fun close() = Unit
            }
            for (ignored in listOf(false, true)) {
                val failure = TimeSettingsRepository(guarded(ignored), pause = {}).applyNtpHosts(identity, listOf("127.0.0.1"))
                assertEquals(if (ignored) TimeSettingStatus.NOT_CONFIRMED else TimeSettingStatus.PERMISSION_DENIED,
                    failure.statuses[TimeSetting.NTP])
                assertFalse(failure.confirmed)
                assertEquals(configured.settings[TimeSetting.NTP], repository.read().settings[TimeSetting.NTP])
            }
            var osDenied = false
            val unprivileged = object : AdbClient by client {
                override fun shell(command: String): ShellResult = if (command.startsWith("settings put global ntp_server")) {
                    client.shell("run-as com.civisrom.tvtimefixer sh -c " + shellQuote(command)).also {
                        osDenied = osDenied || it.permissionDenied
                    }
                } else client.shell(command)
                override fun close() = Unit
            }
            val deniedByAndroid = TimeSettingsRepository(unprivileged, pause = {}).applyNtpHosts(identity, listOf("127.0.0.1"))
            assertTrue("Android Binder must reject the application UID without WRITE_SECURE_SETTINGS", osDenied)
            assertEquals(TimeSettingStatus.PERMISSION_DENIED, deniedByAndroid.statuses[TimeSetting.NTP])
            assertEquals(configured.settings[TimeSetting.NTP], repository.read().settings[TimeSetting.NTP])
            println("Time features: real Android permission denial under the app UID left NTP unchanged PASS")

            val different = identity.copy(digest = "f".repeat(64).let { if (it == identity.digest) "a".repeat(64) else it })
            assertFalse(repository.apply(different, original.settings).identityMatches)
            assertEquals(configured.settings, repository.read().settings)

            var now = SystemClock.elapsedRealtime()
            val monitor = ClockMonitor { now }
            monitor.start(); monitor.add(check)
            now += CLOCK_MONITOR_INTERVAL_MS
            monitor.add(skipped = ClockSampleSkip.BUSY)
            assertEquals(2, monitor.state.samples.size)
            for (reason in listOf(ClockMonitorEnd.USER, ClockMonitorEnd.BACKGROUND, ClockMonitorEnd.DISCONNECTED)) {
                monitor.start(); monitor.add(check); monitor.stop(reason)
                now += CLOCK_MONITOR_INTERVAL_MS
                assertFalse(monitor.due()); assertEquals(reason, monitor.state.ended)
            }
            val secret = "PAIR_CODE_123456 ${address} serial-private raw-command-private"
            val report = diagnosticExport(DiagnosticExportState("2.6.5", android.os.Build.VERSION.SDK_INT,
                original.apiLevel, DiagnosticTransport.NETWORK, true, true, check, true,
                monitor = monitor.state, elapsedNow = SystemClock.elapsedRealtime()), DiagnosticSnapshot(events = listOf(
                DiagnosticEvent(1, 0, Operation.TIME_SETTINGS, Outcome.FAILED, details = secret))))
            assertFalse(report.contains(secret)); assertFalse(report.contains(address.toString()))
            assertFalse(report.contains(identity.digest)); assertFalse(report.contains("::1"))
            assertTrue(report.contains("clock_source_current=unconfirmed"))
            println("Time features: profiles, negative permission/readback, identity refusal, bounded monitoring and privacy PASS")

            // Corruption must fail closed and must not silently replace the surviving baseline file.
            val damaged = File(file.parentFile, file.name + ".damaged")
            damaged.writeBytes(file.readBytes().take(8).toByteArray())
            val bytes = damaged.readBytes()
            try {
                assertTrue(runCatching { TimeSettingsStore(damaged).saveSnapshot(baseline) }.isFailure)
                assertArrayEquals(bytes, damaged.readBytes())
            } finally { damaged.delete() }
            assertTrue(repository.apply(identity, TimeSettingsStore(file).read().snapshots.single().settings).confirmed)
            assertEquals(original.settings, repository.read().settings)
            println("Time features: persisted original raw NTP / automatic flags / zone restored and read back PASS")
        } finally {
            val restored = repository.apply(identity, original.settings)
            val actual = repository.read()
            connector.disconnect()
            file.delete()
            assertTrue("Final restoration: $restored", restored.confirmed)
            assertEquals("Final settings differ from the captured baseline", original.settings, actual.settings)
        }
    }
}
