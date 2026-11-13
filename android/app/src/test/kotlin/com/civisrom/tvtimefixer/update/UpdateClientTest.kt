package com.civisrom.tvtimefixer.update

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Настоящий HTTP на 127.0.0.1: редиректы, коды ответа, обрыв и подмена файла. */
class UpdateClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var directory: File
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var release = ""
    private val requests = mutableListOf<String>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        base = "http://127.0.0.1:${server.address.port}"
        directory = Files.createTempDirectory("update-test").toFile()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            synchronized(requests) { requests += "$path ${exchange.requestHeaders.getFirst("User-Agent")}" }
            fun send(code: Int, body: ByteArray) {
                exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            when (path) {
                "/releases/latest" -> send(200, release.toByteArray())
                "/limited/releases/latest" -> send(403, "{}".toByteArray())
                "/missing/releases/latest" -> send(404, "{}".toByteArray())
                "/download/v9.9.9/AndroidTVTimeFixer-9.9.9.apk" -> {
                    // Как GitHub: ссылка на выпуск перенаправляет на хранилище файлов.
                    exchange.responseHeaders.add("Location", "$base/storage/apk")
                    send(302, ByteArray(0))
                }
                "/storage/apk" -> send(200, apk)
                "/download/v9.9.9/AndroidTVTimeFixer-9.9.9.apk.sha256" ->
                    send(200, "$sha  AndroidTVTimeFixer-9.9.9.apk\n".toByteArray())
                else -> send(404, ByteArray(0))
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        directory.deleteRecursively()
    }

    private fun client(prefix: String = "") = UpdateClient(UpdateSource("$base$prefix/releases/latest", "$base/download/"), "AndroidTVTimeFixer/test")

    private fun releaseJson(digest: String? = sha, size: Int = apk.size, checksum: Boolean = false) = """
        {"tag_name":"v9.9.9","draft":false,"prerelease":false,"assets":[
          {"name":"AndroidTVTimeFixer-9.9.9.apk","size":$size,
           "browser_download_url":"$base/download/v9.9.9/AndroidTVTimeFixer-9.9.9.apk"
           ${digest?.let { ",\"digest\":\"sha256:$it\"" } ?: ""}}
          ${if (checksum) ",{\"name\":\"AndroidTVTimeFixer-9.9.9.apk.sha256\",\"size\":95,\"browser_download_url\":\"$base/download/v9.9.9/AndroidTVTimeFixer-9.9.9.apk.sha256\"}" else ""}
        ]}""".trimIndent()

    private fun error(block: suspend () -> Unit): UpdateError = runBlocking {
        try {
            block(); fail("ожидалась ошибка"); throw AssertionError()
        } catch (e: UpdateException) { e.error }
    }

    private val target get() = File(directory, "AndroidTVTimeFixer-9.9.9.apk")
    private val partial get() = File(directory, "AndroidTVTimeFixer-9.9.9.apk.part")

    @Test
    fun `проверка и загрузка проходят и сверяют SHA-256`() = runBlocking {
        release = releaseJson()
        val update = client().latestRelease()
        assertEquals(AppVersion(9, 9, 9), update.version)
        var progress = 0L
        client().download(update, target) { progress = it }
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong(), progress)
        assertFalse(partial.exists())
        assertTrue(requests.all { it.endsWith("AndroidTVTimeFixer/test") })
    }

    @Test
    fun `сумма из файла sha256, если GitHub не прислал digest`() = runBlocking {
        release = releaseJson(digest = null, checksum = true)
        client().download(client().latestRelease(), target) {}
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun `подменённый файл удаляется`() {
        release = releaseJson(digest = "0".repeat(64))
        assertEquals(UpdateError.CHECKSUM, error { client().download(client().latestRelease(), target) {} })
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun `размер не совпал с объявленным`() {
        release = releaseJson(size = apk.size - 1)
        assertEquals(UpdateError.CHECKSUM, error { client().download(client().latestRelease(), target) {} })
        release = releaseJson(size = apk.size + 1)
        assertEquals(UpdateError.CHECKSUM, error { client().download(client().latestRelease(), target) {} })
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun `коды ответа GitHub`() {
        assertEquals(UpdateError.RATE_LIMITED, error { client("/limited").latestRelease() })
        assertEquals(UpdateError.NOT_FOUND, error { client("/missing").latestRelease() })
        release = "not json"
        assertEquals(UpdateError.BAD_RESPONSE, error { client().latestRelease() })
    }

    @Test
    fun `нет сети`() {
        server.stop(0)
        assertEquals(UpdateError.NETWORK, error { client().latestRelease() })
    }

    @Test
    fun `отмена удаляет недокачанный файл`() = runBlocking {
        release = releaseJson()
        val update = client().latestRelease()
        val job = launch(Dispatchers.IO) { client().download(update, target) { cancel() } }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }
}
