package com.civisrom.tvtimefixer.terminal

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.boundedAdbCommand
import com.civisrom.tvtimefixer.adb.readStreamingShell
import java.io.File
import java.io.IOException

/** Runs on the existing, explicitly selected transport, under the device operation mutex. */
internal class TerminalExecutor(private val files: TerminalFiles, private val session: TerminalSession) {
    fun execute(client: AdbClient, command: TerminalCommand): Int? =
        boundedAdbCommand(TERMINAL_TIMEOUT_MS.toLong(), client::close) {
            // Local validation errors must not tear down a healthy transport.
            try { Result.success(when (command) {
                is TerminalCommand.Shell -> shell(client, command.text)
                is TerminalCommand.Adb -> adb(client, command)
                else -> throw TerminalException(TerminalProblem.ARGUMENTS)
            }) } catch (e: TerminalException) { Result.failure(e) }
            catch (e: AdbRemoteException) { session.append(e.response, true); Result.success(1) }
        }.getOrThrow()

    private fun shell(client: AdbClient, command: String): Int? {
        val service = (if (client.shellV2Supported) "shell,v2,raw:" else "shell:") + command
        return client.openService(service, TERMINAL_TIMEOUT_MS).use {
            readStreamingShell(it.source, client.shellV2Supported, session::append)
        }
    }

    private fun adb(client: AdbClient, command: TerminalCommand.Adb): Int? {
        val args = command.arguments
        when (command.name) {
            "logcat" -> return shell(client, "logcat " + args.joinToString(" ", transform = ::shellQuote))
            "uninstall" -> {
                if (args.isEmpty()) invalid()
                return shell(client, "pm uninstall " + args.joinToString(" ", transform = ::shellQuote))
            }
            "exec-out" -> {
                if (args.isEmpty()) invalid()
                // This screen is textual; binary files use SYNC pull.
                return shell(client, args.joinToString(" ", transform = ::shellQuote))
            }
            "push" -> {
                if (args.size != 2) invalid()
                val local = inputFile(args[0])
                val remote = if (args[1].endsWith('/')) args[1] + local.name else args[1]
                session.transferring(remote, local.name, false)
                local.inputStream().use { AdbFileTransfer(client).push(it, remote, session::progress) }
                return 0
            }
            "pull" -> {
                if (args.size !in 1..2) invalid()
                val name = args.getOrElse(1) { files.uniqueName(args[0].substringAfterLast('/')) }
                session.transferring(args[0], name, true)
                files.receive(name) { AdbFileTransfer(client).pull(args[0], it, session::progress) }
                return 0
            }
            "install", "install-multiple" -> return install(client, args, command.name == "install-multiple")
            "reboot", "root", "unroot", "remount", "disable-verity", "enable-verity", "usb", "tcpip" -> {
                val suffix = when (command.name) {
                    "reboot" -> {
                        if (args.size > 1 || args.any { it !in setOf("bootloader", "recovery", "sideload", "sideload-auto-reboot") }) invalid()
                        args.firstOrNull().orEmpty()
                    }
                    "tcpip" -> {
                        if (args.size != 1 || args[0].toIntOrNull() !in 1..65535) invalid()
                        args[0]
                    }
                    else -> { if (args.isNotEmpty()) invalid(); "" }
                }
                client.openService("${command.name}:$suffix", TERMINAL_TIMEOUT_MS).use {
                    readStreamingShell(it.source, false, session::append)
                }
                // These services return text, not a shell exit packet.
                return null
            }
            "bugreport" -> { if (args.isNotEmpty()) invalid(); return shell(client, "bugreport") }
            else -> throw TerminalException(TerminalProblem.HOST_COMMAND)
        }
    }

    private fun inputFile(name: String): File = files.resolve(name).takeIf { it.isFile }
        ?: throw TerminalException(TerminalProblem.FILE)

    private fun install(client: AdbClient, args: List<String>, multiple: Boolean): Int {
        val options = mutableListOf<String>()
        var i = 0
        while (i < args.size && args[i].startsWith('-')) {
            val flag = args[i++]
            when (flag) {
                "-r", "-t", "-d", "-g", "--instant", "--full", "--dont-kill" -> options += flag
                "--user", "-i", "--abi" -> { options += flag; options += args.getOrNull(i++) ?: invalid() }
                "--streaming" -> Unit
                else -> invalid()
            }
        }
        val apks = args.drop(i).map(::inputFile)
        if (apks.isEmpty() || (!multiple && apks.size != 1) || apks.any { it.length() == 0L }) invalid()
        val flags = options.joinToString(" ", transform = ::shellQuote)
        if (!multiple) {
            val result = installStream(client, "pm install $flags -S ${apks.single().length()}", apks.single())
            session.append(result)
            return if (result.lineSequence().any { it.trim() == "Success" }) 0 else 1
        }
        val created = packageCommand(client, "pm install-create $flags")
        session.append(created)
        val id = Regex("(?m)^Success:.*\\[(\\d+)\\]\\s*$").find(created)?.groupValues?.get(1)
            ?: return 1
        var committed = false
        var cancelled = false
        var transferred = 0L
        try {
            apks.forEachIndexed { index, file ->
                val result = installStream(client, "pm install-write -S ${file.length()} $id split$index -", file, transferred)
                session.append(result)
                if (!result.trimStart().startsWith("Success")) return 1
                transferred += file.length()
            }
            val result = packageCommand(client, "pm install-commit $id")
            session.append(result)
            committed = result.trim() == "Success"
            return if (committed) 0 else 1
        } catch (e: Exception) {
            cancelled = e is InterruptedException || e is kotlinx.coroutines.CancellationException || e is java.net.SocketTimeoutException
            throw e
        } finally {
            if (!committed && !cancelled && !Thread.currentThread().isInterrupted && client.isAlive()) {
                runCatching { boundedAdbCommand(15_000, client::close) { packageCommand(client, "pm install-abandon $id") } }
            }
        }
    }

    private fun packageCommand(client: AdbClient, command: String): String =
        client.openService("exec:$command", TERMINAL_TIMEOUT_MS).use { readPackageResponse(it.source) }

    private fun installStream(client: AdbClient, command: String, file: File, previousBytes: Long = 0): String =
        client.openService("exec:$command", TERMINAL_TIMEOUT_MS).use { service ->
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    interrupted()
                    val count = input.read(buffer)
                    if (count == -1) break
                    service.sink.write(buffer, 0, count).flush()
                    total += count; session.progress(previousBytes + total)
                }
            }
            readPackageResponse(service.source)
        }

    private fun readPackageResponse(source: okio.BufferedSource): String {
        val result = StringBuilder()
        readStreamingShell(source, false) { text, _ ->
            if (result.length + text.length > 65536) throw IOException("Oversized install response")
            result.append(text)
        }
        return result.toString()
    }

    private fun invalid(): Nothing = throw TerminalException(TerminalProblem.ARGUMENTS)
}
