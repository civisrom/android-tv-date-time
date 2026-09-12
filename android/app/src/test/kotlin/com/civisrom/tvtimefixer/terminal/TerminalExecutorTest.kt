package com.civisrom.tvtimefixer.terminal

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.AdbService
import com.civisrom.tvtimefixer.adb.ShellResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class ServiceClient(override val shellV2Supported: Boolean = false) : AdbClient {
    val responses = ArrayDeque<Buffer>()
    val sent = mutableListOf<Pair<String, Buffer>>()
    val shellCommands = mutableListOf<String>()
    val shellResults = ArrayDeque<ShellResult>()
    var closed = false
    var serviceCloses = 0
    override fun isAlive() = !closed
    override fun close() { closed = true }
    override fun shell(command: String): ShellResult { shellCommands += command; return shellResults.removeFirst() }
    override fun openService(destination: String, timeoutMs: Int): AdbService {
        assertEquals(TERMINAL_TIMEOUT_MS, timeoutMs)
        val packageAction = destination.startsWith("exec:pm install-create") || destination.startsWith("exec:pm install-commit") ||
            destination.startsWith("exec:pm install-abandon")
        val response = if (packageAction) {
            shellCommands += destination.removePrefix("exec:")
            val result = shellResults.removeFirst()
            Buffer().writeUtf8(result.output + result.errorOutput)
        } else responses.removeFirst()
        val request = Buffer()
        sent += destination to request
        return object : AdbService {
            override val source = response
            override val sink = request
            override fun close() { serviceCloses++ }
        }
    }
}

class TerminalExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files get() = TerminalFiles(File(temporary.root, "documents"))
    private fun file(name: String, bytes: ByteArray = "fixture APK".toByteArray()): File = files.receive(name) { it.write(bytes) }
    private fun response(id: String, bytes: ByteArray) = Buffer().writeUtf8(id).writeIntLe(bytes.size).write(bytes)
    private fun executor(session: TerminalSession = TerminalSession()) = TerminalExecutor(files, session)

    @Test fun `APK bytes stream to selected device package manager with explicit size and no local path`() {
        val local = file("my app'; touch bad.apk", ByteArray(100_001) { (it % 251).toByte() })
        val client = ServiceClient().apply { responses += Buffer().writeUtf8("Success\n") }
        val session = TerminalSession().apply { edit("adb install -r " + shellQuote(local.name)); start("TV") }
        val result = executor(session).execute(client, parseTerminalCommand(session.state.value.draft))
        session.finish(result)
        assertEquals(0, result)
        assertTrue(client.sent.single().first.startsWith("exec:pm install "))
        assertTrue(client.sent.single().first.endsWith("-S 100001"))
        assertFalse(client.sent.single().first.contains("touch"))
        assertArrayEquals(local.readBytes(), client.sent.single().second.readByteArray())
        assertEquals(100001L, session.state.value.transferred)
        assertFalse(client.closed)
        assertEquals(1, client.serviceCloses)
    }

    @Test fun `package manager rejection is shown and cannot be mistaken for install success`() {
        file("app.apk")
        val session = TerminalSession().apply { edit("adb install app.apk"); start("TV") }
        val client = ServiceClient().apply { responses += Buffer().writeUtf8("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n") }
        val exit = executor(session).execute(client, parseTerminalCommand(session.state.value.draft))
        session.finish(exit)
        assertEquals(1, exit)
        assertTrue(session.state.value.output.joinToString("") { it.text }.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
    }

    @Test fun `split APK transaction commits only after every write succeeds`() {
        file("base.apk", ByteArray(1000)); file("split.apk", ByteArray(700))
        val client = ServiceClient().apply {
            shellResults += ShellResult("Success: created install session [123]\n", "", 0)
            shellResults += ShellResult("Success\n", "", 0)
            responses += Buffer().writeUtf8("Success: streamed 11 bytes\n")
            responses += Buffer().writeUtf8("Success: streamed 11 bytes\n")
        }
        val session = TerminalSession().apply { edit("adb install-multiple -r base.apk split.apk"); start("TV") }
        session.finish(executor(session).execute(client, parseTerminalCommand(session.state.value.draft)))
        assertEquals(0, session.state.value.exitCode)
        assertEquals(1700L, session.state.value.transferred)
        assertEquals(2, client.sent.count { it.first.startsWith("exec:pm install-write") })
        assertTrue(client.sent.any { it.first == "exec:pm install-write -S 1000 123 split0 -" })
        assertTrue(client.sent.any { it.first == "exec:pm install-write -S 700 123 split1 -" })
        assertTrue(client.shellCommands.last().startsWith("pm install-commit 123"))
        assertTrue(client.shellCommands.none { "abandon" in it })
    }

    @Test fun `failed split write abandons the installation and never commits`() {
        file("base.apk"); file("split.apk")
        val client = ServiceClient().apply {
            shellResults += ShellResult("Success: created install session [123]\n", "", 0)
            shellResults += ShellResult("Success\n", "", 0)
            responses += Buffer().writeUtf8("Failure [invalid APK]\n")
        }
        assertEquals(1, executor().execute(client, parseTerminalCommand("adb install-multiple base.apk split.apk")))
        assertTrue(client.shellCommands.last().startsWith("pm install-abandon 123"))
        assertTrue(client.shellCommands.none { "commit" in it })
    }

    @Test fun `push uses SYNC binary DATA chunks and never shell quoting for file bytes`() {
        val local = file("данные.bin", ByteArray(80_003) { (it % 256).toByte() })
        val client = ServiceClient().apply { responses += Buffer().writeUtf8("OKAY").writeIntLe(42) }
        assertEquals(0, executor().execute(client, parseTerminalCommand("adb push данные.bin /sdcard/Download/")))
        assertEquals("sync:", client.sent.single().first)
        val sent = client.sent.single().second
        assertEquals("SEND", sent.readUtf8(4))
        assertEquals("/sdcard/Download/данные.bin,33206", sent.readUtf8(sent.readIntLe().toLong()))
        val transferred = Buffer()
        while (true) {
            val id = sent.readUtf8(4); val length = sent.readIntLe()
            if (id == "DONE") break
            assertEquals("DATA", id)
            assertTrue(length in 1..65536)
            transferred.write(sent.readByteArray(length.toLong()))
        }
        assertArrayEquals(local.readBytes(), transferred.readByteArray())
        assertEquals("QUIT", sent.readUtf8(4))
    }

    @Test fun `pull preserves binary bytes and ignores DONE length as specified by SYNC v1`() {
        val bytes = ByteArray(20_007) { (it % 251).toByte() }
        val client = ServiceClient().apply { responses += response("DATA", bytes).writeUtf8("DONE").writeIntLe(19) }
        assertEquals(0, executor().execute(client, parseTerminalCommand("adb pull /sdcard/a.bin received.bin")))
        assertArrayEquals(bytes, files.resolve("received.bin").readBytes())
    }

    @Test fun `small and empty uploads report exact bytes and the resolved remote filename`() {
        listOf(0, 7, 684).forEach { size ->
            file("file $size.txt", ByteArray(size) { 42 })
            val session = TerminalSession().apply {
                edit("adb push 'file $size.txt' /sdcard/Download/"); start("SHIELD")
            }
            val client = ServiceClient().apply { responses += Buffer().writeUtf8("OKAY").writeIntLe(0) }
            session.finish(executor(session).execute(client, parseTerminalCommand(session.state.value.draft)))
            assertEquals(0, session.state.value.exitCode)
            assertEquals(size.toLong(), session.state.value.transferred)
            assertEquals(TerminalTransfer("/sdcard/Download/file $size.txt", "file $size.txt", false), session.state.value.transfer)
        }
    }

    @Test fun `download without local argument reports the filename available for export`() {
        val client = ServiceClient().apply { responses += response("DATA", "seven!!".toByteArray()).writeUtf8("DONE").writeIntLe(0) }
        val session = TerminalSession().apply { edit("adb pull '/sdcard/Download/my file.txt'"); start("SHIELD") }
        session.finish(executor(session).execute(client, parseTerminalCommand(session.state.value.draft)))
        assertEquals(TerminalTransfer("/sdcard/Download/my file.txt", "my file.txt", true), session.state.value.transfer)
        assertEquals(7L, session.state.value.transferred)
        assertEquals("seven!!", files.resolve("my file.txt").readText())
    }

    @Test fun `download without a destination preserves existing copies and reports the new name`() {
        file("app.apk", "previous".toByteArray())
        val client = ServiceClient().apply { responses += response("DATA", "new".toByteArray()).writeUtf8("DONE").writeIntLe(0) }
        val session = TerminalSession().apply { edit("adb pull /sdcard/app.apk"); start("TV") }
        session.finish(executor(session).execute(client, parseTerminalCommand(session.state.value.draft)))
        assertEquals("previous", files.resolve("app.apk").readText())
        assertEquals("new", files.resolve("1-app.apk").readText())
        assertEquals("1-app.apk", session.state.value.transfer?.localName)
    }

    @Test fun `explicit download destination can still replace an existing document`() {
        file("app.apk", "previous".toByteArray())
        val client = ServiceClient().apply { responses += response("DATA", "new".toByteArray()).writeUtf8("DONE").writeIntLe(0) }
        assertEquals(0, executor().execute(client, parseTerminalCommand("adb pull /sdcard/app.apk app.apk")))
        assertEquals("new", files.resolve("app.apk").readText())
    }

    @Test fun `APK names starting with a dash work with an explicit local path`() {
        val apk = file("-demo.apk")
        val client = ServiceClient().apply { responses += Buffer().writeUtf8("Success\n") }
        assertEquals(0, executor().execute(client, parseTerminalCommand("adb install -r './-demo.apk'")))
        assertArrayEquals(apk.readBytes(), client.sent.single().second.readByteArray())
    }

    @Test fun `failed pull retains an existing file and removes partial local files`() {
        file("existing.txt", "old data".toByteArray())
        val client = ServiceClient().apply { responses += response("DATA", "new data".toByteArray()) }
        assertThrows(IOException::class.java) {
            executor().execute(client, parseTerminalCommand("adb pull /sdcard/a.txt existing.txt"))
        }
        assertEquals("old data", files.resolve("existing.txt").readText())
        assertEquals(listOf("existing.txt"), files.directory.listFiles()!!.map { it.name })
        assertTrue(client.closed)
    }

    @Test fun `SYNC FAIL details remain visible without closing a healthy device connection`() {
        val client = ServiceClient().apply { responses += response("FAIL", "Permission denied".toByteArray()) }
        val session = TerminalSession().apply { edit("adb pull /private/data file.bin"); start("TV") }
        val exit = executor(session).execute(client, parseTerminalCommand(session.state.value.draft))
        session.finish(exit)
        assertEquals(1, exit)
        assertEquals("Permission denied", session.state.value.output.single().text)
        assertFalse(client.closed)
        assertTrue(files.list().isEmpty())
    }

    @Test fun `malformed SYNC lengths are rejected before allocating their payload`() {
        listOf(-1, 65537, Int.MAX_VALUE).forEach { size ->
            val client = ServiceClient().apply { responses += Buffer().writeUtf8("DATA").writeIntLe(size) }
            assertThrows(IOException::class.java) {
                executor().execute(client, parseTerminalCommand("adb pull /sdcard/a.bin received.bin"))
            }
            assertFalse(files.resolve("received.bin").exists())
        }
    }

    @Test fun `invalid local paths host commands and flags never touch or close the transport`() {
        val client = ServiceClient()
        listOf("adb install missing.apk", "adb push ../private /sdcard/a", "adb forward tcp:1 tcp:2",
            "adb install --unknown app.apk", "adb reboot recovery extra", "adb tcpip 0", "adb bugreport ignored.zip").forEach {
            assertThrows(TerminalException::class.java) { executor().execute(client, parseTerminalCommand(it)) }
        }
        assertTrue(client.sent.isEmpty())
        assertFalse(client.closed)
    }

    @Test fun `workspace rejects traversal absolute external files and symlinks`() {
        files.directory.mkdirs()
        val outside = temporary.newFile("private.bin")
        listOf("../private.bin", outside.absolutePath, ".hidden", "dir/file.apk").forEach {
            assertThrows(TerminalException::class.java) { files.resolve(it) }
        }
        Files.createSymbolicLink(File(files.directory, "link.apk").toPath(), outside.toPath())
        assertThrows(TerminalException::class.java) { files.resolve("link.apk") }
        assertTrue(files.list().isEmpty())
    }

    @Test fun `import generates distinct document names and rolls back on failure`() {
        file("app.apk")
        assertEquals("1-app.apk", files.uniqueName("app.apk"))
        assertEquals("app.apk", files.uniqueName("/elsewhere/app.apk").removePrefix("1-"))
        assertThrows(IOException::class.java) {
            files.receive("new.apk") { it.write(byteArrayOf(1)); throw IOException("fixture") }
        }
        assertFalse(files.resolve("new.apk").exists())
        assertTrue(files.directory.listFiles()!!.none { it.name.startsWith('.') })
    }

    @Test fun `long imported names keep their extension and fit a UTF8 filename even after collisions`() {
        for (base in listOf("a".repeat(280), "文".repeat(110), "😀".repeat(110))) {
            repeat(12) {
                val name = files.uniqueName("$base.apk")
                assertTrue(name.endsWith(".apk"))
                assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
                assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                assertEquals(name, file(name).name)
            }
        }
        assertEquals(36, files.list().size)
    }

    @Test fun `removing a document only deletes that local copy and rejects external paths`() {
        file("old.apk"); file("keep.apk")
        val outside = temporary.newFile("outside.apk")
        files.remove("old.apk")
        assertEquals(listOf("keep.apk"), files.list().map { it.name })
        assertThrows(TerminalException::class.java) { files.remove("../outside.apk") }
        assertThrows(TerminalException::class.java) { files.remove("missing.apk") }
        assertTrue(outside.isFile)
    }

    @Test fun `cancelled document import cannot publish a partially copied file`() {
        file("keep.apk")
        try {
            assertThrows(InterruptedException::class.java) {
                files.receive("keep.apk") {
                    it.write(byteArrayOf(1))
                    Thread.currentThread().interrupt()
                }
            }
        } finally { Thread.interrupted() }
        assertEquals("fixture APK", files.resolve("keep.apk").readText())
        assertEquals(listOf("keep.apk"), files.directory.listFiles()!!.map { it.name })
    }
}
