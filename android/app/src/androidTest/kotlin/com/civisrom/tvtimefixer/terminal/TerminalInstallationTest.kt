package com.civisrom.tvtimefixer.terminal

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.AdbService
import com.civisrom.tvtimefixer.adb.ShellResult
import java.io.File
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.sink
import okio.source
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Package Manager installation using shell stdin. ADB framing is covered separately. */
class TerminalInstallationTest {
    @Test fun imported_APK_is_streamed_installed_updated_and_removed_on_the_test_device() {
        if (Build.VERSION.SDK_INT < 31) {
            assumeTrue("Bidirectional UiAutomation shell requires API 31", false)
            return
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val workspace = TerminalFiles(File(instrumentation.targetContext.cacheDir, "terminal-install-test"))
        val packageName = "com.civisrom.tvtimefixer.terminalfixture"
        val client = object : AdbClient {
            override fun isAlive() = true
            override fun close() = Unit
            override fun shell(command: String): ShellResult = error("Unexpected buffered shell")
            override fun openService(destination: String, timeoutMs: Int): AdbService {
                check(destination.startsWith("exec:"))
                // UiAutomation uses Runtime.exec(String), whereas adbd exec uses a shell.
                // The fixture has plain arguments; remove shell quoting before passing argv.
                val args = splitAdbArguments(destination.removePrefix("exec:"))
                check(args.all { it.matches(Regex("[A-Za-z0-9_.:-]+")) })
                val descriptors = automation.executeShellCommandRw(args.joinToString(" "))
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).source().buffer()
                val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).sink().buffer()
                val response = object : ForwardingSource(input) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        // UiAutomation retains its stdout pipe until the stdin copier finishes.
                        // ADB itself closes the service when pm exits after consuming -S bytes.
                        output.close()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                return object : AdbService {
                    override val source = response
                    override val sink = output
                    override fun close() { runCatching { output.close() }; response.close() }
                }
            }
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            instrumentation.context.assets.open("terminal-fixture.apk").use { input ->
                workspace.receive("fixture.apk") { input.copyTo(it) }
            }
            instrumentation.context.assets.open("terminal-fixture-split.apk").use { input ->
                workspace.receive("split.apk") { input.copyTo(it) }
            }
            val session = TerminalSession()
            listOf("adb install -t fixture.apk", "adb install -r -t fixture.apk",
                "adb install-multiple -r -t fixture.apk split.apk").forEach { installation ->
                session.edit(installation)
                val command = session.start("instrumentation target")!!
                val exit = TerminalExecutor(workspace, session).execute(client, command)
                session.finish(exit)
                assertEquals(session.state.value.output.joinToString("") { it.text }, 0, exit)
                assertTrue(shell("pm path $packageName").startsWith("package:"))
                if (installation.startsWith("adb install-multiple")) {
                    assertEquals(2, shell("pm path $packageName").lineSequence().count { it.startsWith("package:") })
                    assertEquals(workspace.resolve("fixture.apk").length() + workspace.resolve("split.apk").length(),
                        session.state.value.transferred)
                }
            }
        } finally {
            shell("pm uninstall $packageName")
            workspace.resolve("fixture.apk").delete()
            workspace.resolve("split.apk").delete()
        }
        assertFalse(shell("pm path $packageName").startsWith("package:"))
    }
}
