package com.civisrom.tvtimefixer.adb

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.data.parseDeviceAddress
import com.civisrom.tvtimefixer.device.*
import com.civisrom.tvtimefixer.terminal.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: the supplied address must belong to a disposable, already authorized test device. */
class NetworkAdbIntegrationTest {
    @Test fun real_network_terminal_files_packages_and_device_settings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = InstrumentationRegistry.getArguments().getString("real_adb_address")
        assumeTrue("Requires an explicitly supplied disposable ADB target", input != null)
        val address = checkNotNull(parseDeviceAddress(checkNotNull(input)))
        val connector = DeviceConnector(KadbAdbClientFactory(5_000, 8_000))
        assertEquals(ConnectionState.Connected(address), connector.connect(address))
        val client = checkNotNull(connector.activeClient)
        val files = TerminalFiles(File(instrumentation.targetContext.cacheDir, "network-adb-test"))
        val session = TerminalSession()
        val remote = "/data/local/tmp/tvtimefixer-${SystemClock.elapsedRealtime()}"
        val fixturePackage = "com.civisrom.tvtimefixer.terminalfixture"
        fun execute(command: String, expected: Int = 0) {
            session.edit(command)
            val parsed = checkNotNull(session.start(address.toString()))
            session.finish(TerminalExecutor(files, session).execute(client, parsed))
            assertEquals(command + "\n" + session.state.value.output.joinToString("") { it.text },
                expected, session.state.value.exitCode)
        }
        fun output() = session.state.value.output.joinToString("") { it.text }
        val repository = DeviceRepository(client)
        val originalNtp = repository.currentNtpServer()
        val originalZone = client.shell("getprop persist.sys.timezone").trimmedOutput
        val originalAutoZone = client.shell("cmd time_zone_detector is_auto_detection_enabled").trimmedOutput
        try {
            execute("mkdir -p $remote")
            execute("printf 'Привет 世界\\n'; printf 'ошибка\\n' >&2; exit 7", 7)
            assertTrue(session.state.value.output.any { !it.error && "Привет 世界" in it.text })
            assertTrue(session.state.value.output.any { it.error && "ошибка" in it.text })
            execute("for x in 'a b' c; do printf '[%s]\\n' \"\$x\"; done | head -n 1")
            assertEquals("[a b]\n", output())
            execute("head -c 180000 /dev/zero | tr '\\000' x; printf 'OUTPUT_END\\n'")
            assertTrue(session.state.value.truncated)
            assertTrue(output().endsWith("OUTPUT_END\n"))
            assertTrue(output().length <= TERMINAL_OUTPUT_LIMIT)
            execute("printf '\\377TAIL\\n'")
            assertTrue(output().endsWith("\ufffdTAIL\n"))
            execute("tvtimefixer_nonexistent_command", 127)
            execute("printf 'after-error\\n'")
            assertEquals("after-error\n", output())
            println("Network ADB: Unicode, shell quoting, stdout/stderr, exit codes, bounded output verified")

            for (size in listOf(0, 1, 80_003, 1_048_579)) {
                val bytes = ByteArray(size) { (it % 251).toByte() }
                files.receive("payload ' unicode-я.bin") { it.write(bytes) }
                val name = shellQuote("payload ' unicode-я.bin")
                execute("adb push $name $remote/")
                val source = shellQuote("$remote/payload ' unicode-я.bin")
                execute("adb pull $source received.bin")
                assertArrayEquals(bytes, files.resolve("received.bin").readBytes())
                assertEquals(size.toLong(), session.state.value.transferred)
            }
            val previous = files.resolve("received.bin").readBytes()
            execute("adb pull $remote/missing received.bin", 1)
            assertArrayEquals(previous, files.resolve("received.bin").readBytes())
            execute("adb push received.bin /proc/tvtimefixer-forbidden", 1)
            assertEquals(ConnectionState.Connected(address), connector.checkConnection())
            println("Network ADB: binary/empty/Unicode files, missing source and denied destination verified")

            for ((asset, name) in listOf("terminal-fixture.apk" to "base.apk", "terminal-fixture-split.apk" to "split.apk")) {
                instrumentation.context.assets.open(asset).use { inputStream -> files.receive(name) { inputStream.copyTo(it) } }
            }
            execute("adb install -t base.apk")
            execute("adb install -r -t base.apk")
            execute("adb install-multiple -r -t base.apk split.apk")
            assertEquals(2, client.shell("pm path $fixturePackage").output.lineSequence().count { it.startsWith("package:") })
            files.receive("invalid.apk") { it.write(byteArrayOf(1, 2, 3)) }
            execute("adb install invalid.apk", 1)
            execute("adb uninstall $fixturePackage")
            println("Network ADB: install/update/split APK, invalid APK and uninstall verified")

            val info = repository.readDeviceInfo()
            assertTrue(info.model.isNotBlank())
            assertTrue(info.apiLevel.toInt() >= 23)
            assertEquals(NtpUpdateResult.InvalidServer, repository.setNtpServer("bad;value"))
            val change = repository.setNtpServer("time.google.com") as NtpUpdateResult.Applied
            assertEquals("time.google.com", repository.currentNtpServer())
            assertTrue(repository.undoNtpServer(change) is NtpUpdateResult.Applied)
            assertEquals(originalNtp, repository.currentNtpServer())
            val reset = repository.resetNtpServer() as NtpUpdateResult.Applied
            assertEquals("null", repository.currentNtpServer())
            assertTrue(repository.undoNtpServer(reset) is NtpUpdateResult.Applied)
            InstrumentationRegistry.getArguments().getString("real_ntp_host")?.let { host ->
                val query = com.civisrom.tvtimefixer.net.UdpSntpClient()
                repeat(3) {
                    val sample = query.query(host)
                    assertTrue(sample.rttMs >= 0)
                    assertNotNull(sample.referenceTimeMillis)
                }
                val clock = DeviceTimeVerifier(query, SystemClock::elapsedRealtime).verify(client, host)
                assertEquals(DeviceTimeStatus.MATCH, clock.status)
                println("Network ADB: real UDP NTP and device clock comparison verified")
            }
            val zones = TimeZoneRepository(client)
            assertEquals(TimeZoneUpdateResult.Failed(TimeZoneFailure.INVALID_ZONE), zones.setTimeZone("bad/zone"))
            assertEquals(TimeZoneUpdateResult.Applied("Asia/Tokyo"), zones.setTimeZone("Asia/Tokyo"))
            assertEquals("Asia/Tokyo", client.shell("getprop persist.sys.timezone").trimmedOutput)
            println("Network ADB: real device information, NTP apply/reset/undo and timezone verified")
        } finally {
            runCatching { client.shell(if (originalNtp == "null") "settings delete global ntp_server"
                else "settings put global ntp_server ${shellQuote(originalNtp)}") }
            runCatching { TimeZoneRepository(client).setTimeZone(originalZone) }
            if (originalAutoZone in listOf("true", "false")) runCatching {
                client.shell("cmd time_zone_detector set_auto_detection_enabled $originalAutoZone")
            }
            runCatching { client.shell("rm -rf $remote") }
            runCatching { client.shell("pm uninstall $fixturePackage") }
            connector.disconnect()
            files.directory.deleteRecursively()
        }
        assertEquals(ConnectionState.Connected(address), connector.connect(address))
        connector.disconnect()
        assertEquals(ConnectionState.Disconnected, connector.state)
        println("Network ADB: reconnect verified; test settings and files restored")
    }
}
