package com.civisrom.tvtimefixer.terminal

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.AdbService
import com.civisrom.tvtimefixer.adb.ShellResult
import java.io.File
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
                val descriptors = automation.executeShellCommandRw(destination.removePrefix("exec:"))
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).source().buffer()
                val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).sink().buffer()
                return object : AdbService {
                    override val source = input
                    override val sink = output
                    override fun close() { runCatching { output.close() }; input.close() }
                }
            }
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            instrumentation.context.assets.open("terminal-fixture.apk").use { input ->
                workspace.receive("fixture.apk") { input.copyTo(it) }
            }
            val session = TerminalSession()
            repeat(2) {
                session.edit("adb install -r -t fixture.apk")
                val command = session.start("instrumentation target")!!
                val exit = TerminalExecutor(workspace, session).execute(client, command)
                session.finish(exit)
                assertEquals(session.state.value.output.joinToString("") { it.text }, 0, exit)
                assertTrue(shell("pm path $packageName").startsWith("package:"))
            }
        } finally {
            shell("pm uninstall $packageName")
            workspace.resolve("fixture.apk").delete()
        }
        assertFalse(shell("pm path $packageName").startsWith("package:"))
    }
}
