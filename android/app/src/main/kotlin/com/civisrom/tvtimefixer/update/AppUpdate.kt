package com.civisrom.tvtimefixer.update

import org.json.JSONException
import org.json.JSONObject

/**
 * Номер версии вида 2.6.7. Любой суффикс (-dev, -run12) означает
 * предварительную сборку: она младше выпуска с теми же цифрами.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val prerelease: Boolean = false) :
    Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch, { !it.prerelease })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})([-+][0-9A-Za-z.+-]*)?""")

        fun parse(value: String): AppVersion? = PATTERN.matchEntire(value.trim())?.let { match ->
            val (major, minor, patch, suffix) = match.destructured
            AppVersion(major.toInt(), minor.toInt(), patch.toInt(), suffix.isNotEmpty())
        }
    }
}

/** Опубликованный выпуск с подписанным APK для Android. */
data class ReleaseUpdate(
    val version: AppVersion,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** SHA-256 из ответа GitHub; если его нет, сумма берётся из файла [checksumUrl]. */
    val sha256: String?,
    val checksumUrl: String?,
)

enum class UpdateError {
    NETWORK, RATE_LIMITED, NOT_FOUND, BAD_RESPONSE, NO_APK, CHECKSUM, STORAGE,
    PACKAGE_MISMATCH, NOT_NEWER, SIGNATURE_MISMATCH,
    INSTALL_ABORTED, INSTALL_BLOCKED, INSTALL_CONFLICT, INSTALL_STORAGE, INSTALL_INVALID, INSTALL_FAILED,
}

class UpdateException(val error: UpdateError, cause: Throwable? = null) : Exception(error.name, cause)

const val APK_NAME_PREFIX = "AndroidTVTimeFixer-"
const val MAX_APK_BYTES = 128L * 1024 * 1024
private val SHA256 = Regex("[0-9a-f]{64}")

/**
 * Разбирает ответ GitHub `releases/latest`.
 *
 * Берётся только APK с именем `AndroidTVTimeFixer-<версия тега>.apk` и только
 * по адресу из [downloadPrefix]: неподписанные сборки и чужие ссылки не подходят.
 */
fun parseLatestRelease(json: String, downloadPrefix: String): ReleaseUpdate = try {
    val root = JSONObject(json)
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) throw UpdateException(UpdateError.NOT_FOUND)
    val version = AppVersion.parse(root.getString("tag_name"))?.takeUnless { it.prerelease }
        ?: throw UpdateException(UpdateError.BAD_RESPONSE)
    val apkName = "$APK_NAME_PREFIX$version.apk"
    val assets = root.getJSONArray("assets")
    var apk: JSONObject? = null
    var checksum: JSONObject? = null
    for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        when (asset.optString("name")) {
            apkName -> apk = asset
            "$apkName.sha256" -> checksum = asset
        }
    }
    val found = apk ?: throw UpdateException(UpdateError.NO_APK)
    val url = found.getString("browser_download_url")
    val size = found.getLong("size")
    if (!url.startsWith(downloadPrefix) || size !in 1..MAX_APK_BYTES) throw UpdateException(UpdateError.BAD_RESPONSE)
    val digest = found.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        ?.takeIf { SHA256.matches(it) }
    val checksumUrl = checksum?.getString("browser_download_url")?.takeIf { it.startsWith(downloadPrefix) }
    if (digest == null && checksumUrl == null) throw UpdateException(UpdateError.BAD_RESPONSE)
    ReleaseUpdate(version, apkName, url, size, digest, checksumUrl)
} catch (error: JSONException) {
    throw UpdateException(UpdateError.BAD_RESPONSE, error)
}

/** Файл `.sha256` выпуска: «<сумма>  <имя файла>». */
fun parseChecksumFile(text: String, apkName: String): String {
    val parts = text.trim().split(Regex("\\s+"))
    val digest = parts.firstOrNull()?.lowercase()
    if (parts.size != 2 || digest == null || !SHA256.matches(digest) || parts[1].removePrefix("*") != apkName) {
        throw UpdateException(UpdateError.BAD_RESPONSE)
    }
    return digest
}

/** Сведения о пакете для сверки скачанного APK с установленным приложением. */
data class PackageIdentity(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 сертификатов: у установленного — текущие, у APK — вся история ротации. */
    val signers: Set<String>?,
)

/**
 * Проверка до системного установщика: тот всё равно откажет, но без объяснения
 * причины. Если подписи APK прочитать не удалось, решение остаётся за системой.
 */
fun verifyArchive(installed: PackageIdentity, archive: PackageIdentity): UpdateError? = when {
    archive.packageName != installed.packageName -> UpdateError.PACKAGE_MISMATCH
    archive.versionCode <= installed.versionCode -> UpdateError.NOT_NEWER
    archive.signers.isNullOrEmpty() || installed.signers.isNullOrEmpty() -> null
    !archive.signers.containsAll(installed.signers) -> UpdateError.SIGNATURE_MISMATCH
    else -> null
}
