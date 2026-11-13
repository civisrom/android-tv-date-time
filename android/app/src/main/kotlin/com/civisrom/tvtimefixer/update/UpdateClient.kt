package com.civisrom.tvtimefixer.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Откуда брать сведения о выпуске и с какого адреса разрешено скачивать APK. */
data class UpdateSource(val releaseApiUrl: String, val downloadPrefix: String)

/** Запросы к GitHub Releases: только чтение публичных данных, без токенов. */
class UpdateClient(private val source: UpdateSource, private val userAgent: String) {

    suspend fun latestRelease(): ReleaseUpdate = withContext(Dispatchers.IO) {
        val body = request(source.releaseApiUrl, json = true) { it.readLimited(MAX_JSON_BYTES) }
        parseLatestRelease(body.toString(Charsets.UTF_8), source.downloadPrefix)
    }

    /**
     * Скачивает APK в [target] и сверяет размер и SHA-256. При любой ошибке
     * или отмене недокачанный файл удаляется.
     */
    suspend fun download(release: ReleaseUpdate, target: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val expected = release.sha256 ?: release.checksumUrl!!.let { url ->
            parseChecksumFile(request(url) { it.readLimited(1024) }.toString(Charsets.US_ASCII), release.apkName)
        }
        val partial = File(target.path + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            request(release.apkUrl) { input ->
                storage { partial.outputStream() }.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > release.apkSize) throw UpdateException(UpdateError.CHECKSUM)
                        storage { output.write(buffer, 0, read) }
                        digest.update(buffer, 0, read)
                        onProgress(total)
                    }
                    if (total != release.apkSize) throw UpdateException(UpdateError.CHECKSUM)
                    storage { output.fd.sync() }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) throw UpdateException(UpdateError.CHECKSUM)
            if (!partial.renameTo(target)) throw UpdateException(UpdateError.STORAGE)
        } finally {
            partial.delete()
        }
    }

    private fun <T> request(url: String, json: Boolean = false, read: (InputStream) -> T): T {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw UpdateException(UpdateError.NETWORK, error)
        }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            if (json) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = try { connection.responseCode } catch (error: IOException) {
                throw UpdateException(UpdateError.NETWORK, error)
            }
            when {
                code == 404 -> throw UpdateException(UpdateError.NOT_FOUND)
                code == 403 || code == 429 -> throw UpdateException(UpdateError.RATE_LIMITED)
                code !in 200..299 -> throw UpdateException(UpdateError.BAD_RESPONSE)
            }
            return try {
                connection.inputStream.use(read)
            } catch (error: IOException) {
                throw UpdateException(UpdateError.NETWORK, error)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Ошибка записи на диск отличается от обрыва сети. */
    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw UpdateException(UpdateError.STORAGE, error)
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val bytes = readNBytesCompat(limit + 1)
        if (bytes.size > limit) throw UpdateException(UpdateError.BAD_RESPONSE)
        return bytes
    }

    // InputStream.readNBytes появился только в Android 13.
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
    }
}
