package com.civisrom.tvtimefixer.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateTest {
    private val prefix = "https://github.com/civisrom/android-tv-date-time/releases/download/"
    private val digest = "6e01d88131d8af2d5ed04cf3958d08d6ed4d67596e79c34c796db86646db2d4e"

    private fun asset(name: String, url: String = "${prefix}v2.6.8/$name", size: Long = 12_658_942,
                      digest: String? = "sha256:${this.digest}") =
        """{"name":"$name","size":$size,"browser_download_url":"$url"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    private fun release(vararg assets: String, tag: String = "v2.6.8", prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"html_url":"https://github.com/x","assets":[${assets.joinToString(",")}]}"""

    private fun error(block: () -> Unit): UpdateError = try {
        block(); fail("ожидалась ошибка"); throw AssertionError()
    } catch (e: UpdateException) { e.error }

    @Test
    fun `версии разбираются и сравниваются`() {
        assertEquals(AppVersion(2, 6, 7), AppVersion.parse("2.6.7"))
        assertEquals(AppVersion(2, 6, 7), AppVersion.parse("v2.6.7"))
        assertEquals(AppVersion(2, 6, 7, prerelease = true), AppVersion.parse("2.6.7-dev"))
        assertEquals(AppVersion(0, 0, 0, prerelease = true), AppVersion.parse("0.0.0-run123"))
        assertNull(AppVersion.parse("2.6"))
        assertNull(AppVersion.parse("latest"))
        assertTrue(AppVersion(2, 6, 8) > AppVersion(2, 6, 7))
        assertTrue(AppVersion(2, 10, 0) > AppVersion(2, 9, 9))
        assertTrue(AppVersion(2, 6, 7) > AppVersion(2, 6, 7, prerelease = true))
        assertEquals(0, AppVersion(2, 6, 7).compareTo(AppVersion.parse("v2.6.7")!!))
    }

    @Test
    fun `выбирается подписанный APK текущего тега`() {
        val json = release(asset("unsigned-AndroidTVTimeFixer-2.6.8.apk"), asset("AndroidTVTimeFixer-windows.zip"),
            asset("AndroidTVTimeFixer-2.6.8.apk"), asset("AndroidTVTimeFixer-2.6.8.apk.sha256", size = 95, digest = null))
        val update = parseLatestRelease(json, prefix)
        assertEquals(AppVersion(2, 6, 8), update.version)
        assertEquals("AndroidTVTimeFixer-2.6.8.apk", update.apkName)
        assertEquals("${prefix}v2.6.8/AndroidTVTimeFixer-2.6.8.apk", update.apkUrl)
        assertEquals(12_658_942L, update.apkSize)
        assertEquals(digest, update.sha256)
        assertEquals("${prefix}v2.6.8/AndroidTVTimeFixer-2.6.8.apk.sha256", update.checksumUrl)
    }

    @Test
    fun `без digest используется файл sha256`() {
        val update = parseLatestRelease(release(asset("AndroidTVTimeFixer-2.6.8.apk", digest = null),
            asset("AndroidTVTimeFixer-2.6.8.apk.sha256", size = 95, digest = null)), prefix)
        assertNull(update.sha256)
        assertEquals("${prefix}v2.6.8/AndroidTVTimeFixer-2.6.8.apk.sha256", update.checksumUrl)
    }

    @Test
    fun `сомнительные выпуски отклоняются`() {
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk", digest = null)), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk", url = "https://example.com/AndroidTVTimeFixer-2.6.8.apk")), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk", size = 0)), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk", size = MAX_APK_BYTES + 1)), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk", digest = "sha256:xyz"),), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease(release(tag = "v2.6.8-rc1"), prefix) })
        assertEquals(UpdateError.NOT_FOUND, error { parseLatestRelease(release(
            asset("AndroidTVTimeFixer-2.6.8.apk"), prerelease = true), prefix) })
        assertEquals(UpdateError.NO_APK, error { parseLatestRelease(release(
            asset("unsigned-AndroidTVTimeFixer-2.6.8.apk"), asset("AndroidTVTimeFixer-2.6.7.apk")), prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease("<html>", prefix) })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseLatestRelease("""{"tag_name":"v2.6.8"}""", prefix) })
    }

    @Test
    fun `файл контрольной суммы`() {
        assertEquals(digest, parseChecksumFile("$digest  AndroidTVTimeFixer-2.6.8.apk\n", "AndroidTVTimeFixer-2.6.8.apk"))
        assertEquals(digest, parseChecksumFile("${digest.uppercase()} *AndroidTVTimeFixer-2.6.8.apk", "AndroidTVTimeFixer-2.6.8.apk"))
        assertEquals(UpdateError.BAD_RESPONSE, error { parseChecksumFile("$digest  other.apk", "AndroidTVTimeFixer-2.6.8.apk") })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseChecksumFile("abc  AndroidTVTimeFixer-2.6.8.apk", "AndroidTVTimeFixer-2.6.8.apk") })
        assertEquals(UpdateError.BAD_RESPONSE, error { parseChecksumFile("", "AndroidTVTimeFixer-2.6.8.apk") })
    }

    @Test
    fun `скачанный APK сверяется с установленным`() {
        val installed = PackageIdentity("com.civisrom.tvtimefixer", 130, setOf("release"))
        assertNull(verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 131, setOf("release"))))
        // Ротация ключа: новая подпись, но в истории есть текущая.
        assertNull(verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 131, setOf("release", "next"))))
        assertEquals(UpdateError.PACKAGE_MISMATCH,
            verifyArchive(installed, PackageIdentity("com.example", 131, setOf("release"))))
        assertEquals(UpdateError.NOT_NEWER, verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 130, setOf("release"))))
        assertEquals(UpdateError.NOT_NEWER, verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 129, setOf("release"))))
        assertEquals(UpdateError.SIGNATURE_MISMATCH,
            verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 131, setOf("debug"))))
        // Подписи не прочитались: окончательное решение остаётся за установщиком Android.
        assertNull(verifyArchive(installed, PackageIdentity("com.civisrom.tvtimefixer", 131, null)))
    }

    @Test
    fun `коды установщика переводятся в причины`() {
        assertNull(installStatusError(PackageInstaller.STATUS_SUCCESS))
        assertNull(installStatusError(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(UpdateError.INSTALL_ABORTED, installStatusError(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateError.INSTALL_BLOCKED, installStatusError(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(UpdateError.INSTALL_CONFLICT, installStatusError(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(UpdateError.INSTALL_STORAGE, installStatusError(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(UpdateError.INSTALL_INVALID, installStatusError(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(UpdateError.INSTALL_INVALID, installStatusError(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(UpdateError.INSTALL_FAILED, installStatusError(PackageInstaller.STATUS_FAILURE))
    }
}
