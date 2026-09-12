package com.civisrom.tvtimefixer.terminal

import com.civisrom.tvtimefixer.adb.AdbClient
import com.civisrom.tvtimefixer.adb.AdbService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

internal class AdbRemoteException(val response: String) : IOException("ADB remote operation failed")

/** ADB SYNC v1 работает и на старых приставках, без base64 и shell-редиректов. */
internal class AdbFileTransfer(private val client: AdbClient) {
    fun push(input: InputStream, remote: String, progress: (Long) -> Unit) {
        validateRemote(remote)
        client.openService("sync:", TERMINAL_TIMEOUT_MS).use { service ->
            service.request("SEND", "$remote,33206".toByteArray(Charsets.UTF_8))
            val bytes = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                interrupted()
                val count = input.read(bytes)
                if (count < 0) break
                if (count == 0) continue
                service.request("DATA", bytes.copyOf(count))
                total += count
                progress(total)
            }
            service.sink.writeUtf8("DONE").writeIntLe((System.currentTimeMillis() / 1000).toInt()).flush()
            val id = service.source.readUtf8(4)
            val length = service.source.readIntLe()
            if (id == "FAIL") service.remoteFailure(length)
            if (id != "OKAY") throw IOException("Invalid ADB push response")
            service.quit()
        }
    }

    fun pull(remote: String, output: OutputStream, progress: (Long) -> Unit) {
        validateRemote(remote)
        client.openService("sync:", TERMINAL_TIMEOUT_MS).use { service ->
            service.request("RECV", remote.toByteArray(Charsets.UTF_8))
            var total = 0L
            while (true) {
                interrupted()
                val id = service.source.readUtf8(4)
                val length = service.source.readIntLe()
                if (id == "DONE") break // SYNC v1 specifies that this length is ignored.
                if (id == "FAIL") service.remoteFailure(length)
                if (id != "DATA" || length !in 0..65536) throw IOException("ADB pull rejected or invalid frame")
                output.write(service.source.readByteArray(length.toLong()))
                total += length
                progress(total)
            }
            service.quit()
        }
    }

    private fun AdbService.request(id: String, bytes: ByteArray) {
        sink.writeUtf8(id).writeIntLe(bytes.size).write(bytes).flush()
    }

    private fun AdbService.quit() { sink.writeUtf8("QUIT").writeIntLe(0).flush() }

    private fun AdbService.remoteFailure(length: Int): Nothing {
        if (length !in 0..65536) throw IOException("Invalid SYNC failure")
        throw AdbRemoteException(source.readUtf8(length.toLong()))
    }

    private fun validateRemote(path: String) {
        if (!path.startsWith('/') || '\u0000' in path || path.toByteArray().size > 1024) {
            throw TerminalException(TerminalProblem.ARGUMENTS)
        }
    }
}

internal fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }

/** Local command paths can only refer to the app's explicitly imported/received documents. */
class TerminalFiles(val directory: File) {
    fun resolve(name: String): File {
        if (name.isBlank() || '\u0000' in name) throw TerminalException(TerminalProblem.FILE)
        val root = directory.canonicalFile
        val path = File(name).let { if (it.isAbsolute) it else File(root, name) }.canonicalFile
        if (path.parentFile != root || path.name.startsWith('.')) throw TerminalException(TerminalProblem.FILE)
        return path
    }

    fun list(): List<File> = directory.listFiles()?.filter { it.isFile && !it.name.startsWith('.') &&
        runCatching { resolve(it.name) == it.canonicalFile }.getOrDefault(false) }?.sortedBy { it.name }.orEmpty()

    fun uniqueName(proposed: String): String {
        val base = proposed.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 }
            .trim().takeUnless { it.isBlank() || it.startsWith('.') } ?: "document.bin"
        val extension = base.substringAfterLast('.', "").takeIf { it.length in 1..16 }?.let { ".$it" }.orEmpty()
        val stem = base.removeSuffix(extension)
        var index = 0
        while (true) {
            val prefix = if (index == 0) "" else "$index-"
            // Android filenames are bounded in UTF-8 bytes, not Kotlin characters.
            // Leave room for both the extension and a collision prefix.
            val candidate = prefix + stem.takeUtf8(240 - prefix.length - extension.toByteArray(Charsets.UTF_8).size) + extension
            if (!resolve(candidate).exists()) return candidate
            index++
        }
    }

    private fun String.takeUtf8(limit: Int): String {
        var end = minOf(length, limit)
        while (substring(0, end).toByteArray(Charsets.UTF_8).size > limit) end--
        if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
        return substring(0, end)
    }

    fun receive(name: String, writer: (OutputStream) -> Unit): File {
        directory.mkdirs()
        val destination = resolve(name)
        // Preserve an existing document if a pull/import fails halfway through.
        val temp = File.createTempFile(".transfer-", ".tmp", directory)
        try {
            temp.outputStream().use(writer)
            interrupted()
            if (!temp.renameTo(destination)) throw IOException("Document commit failed")
            return destination
        } finally { temp.delete() }
    }

    fun remove(name: String) {
        val file = resolve(name)
        if (!file.isFile || !file.delete()) throw TerminalException(TerminalProblem.FILE)
    }
}
