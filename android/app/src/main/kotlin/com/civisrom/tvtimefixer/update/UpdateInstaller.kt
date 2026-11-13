package com.civisrom.tvtimefixer.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Действие, которое системный установщик присылает через PendingIntent сессии. */
const val ACTION_UPDATE_INSTALL_STATUS = "com.civisrom.tvtimefixer.action.UPDATE_INSTALL_STATUS"

/** Код результата PackageInstaller → понятная причина; null — успех или ожидание пользователя. */
fun installStatusError(status: Int): UpdateError? = when (status) {
    PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
    PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateError.INSTALL_ABORTED
    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateError.INSTALL_BLOCKED
    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateError.INSTALL_CONFLICT
    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateError.INSTALL_STORAGE
    PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateError.INSTALL_INVALID
    else -> UpdateError.INSTALL_FAILED
}

/** Установка через системный PackageInstaller: подтверждает её сам пользователь. */
class UpdateInstaller(private val context: Context) {
    private val packageManager get() = context.packageManager

    /** До Android 8 разрешение даёт общий переключатель «Неизвестные источники» в окне установщика. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    fun installedIdentity(): PackageIdentity {
        val info = packageInfo(context.packageName)
        return PackageIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info), currentSigners(info))
    }

    fun archiveIdentity(apk: File): PackageIdentity {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageArchiveInfo(apk.path, signatureFlags())
            ?: throw UpdateException(UpdateError.PACKAGE_MISMATCH)
        return PackageIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info), allowedSigners(info))
    }

    /** Создаёт сессию заранее: её номер нужен до того, как система пришлёт первый статус. */
    fun createSession(apk: File): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        return try {
            packageManager.packageInstaller.createSession(params)
        } catch (error: java.io.IOException) {
            throw UpdateException(UpdateError.INSTALL_STORAGE, error)
        }
    }

    /** Передаёт APK системе. Результат придёт широковещательным [ACTION_UPDATE_INSTALL_STATUS]. */
    fun commit(sessionId: Int, apk: File) {
        val installer = packageManager.packageInstaller
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(ACTION_UPDATE_INSTALL_STATUS).setPackage(context.packageName)
                // Система дописывает в intent статус и окно подтверждения, поэтому
                // с Android 12 он обязан быть изменяемым; адресат при этом явный.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw if (error is java.io.IOException) UpdateException(UpdateError.INSTALL_STORAGE, error) else error
        }
    }

    fun abandon(sessionId: Int) {
        runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    private fun packageInfo(name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(signatureFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(name, signatureFlags())
        }

    /** Сертификаты, которыми подписан установленный пакет сейчас. */
    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signing ->
                if (signing.hasMultipleSigners()) signing.apkContentsSigners?.toList()
                else signing.signingCertificateHistory?.lastOrNull()?.let(::listOf)
            }?.digests()
        } else {
            info.signatures?.toList()?.digests()
        }

    /** Сертификаты, которые новый APK вправе заменить: текущие и прежние по цепочке ротации. */
    @Suppress("DEPRECATION")
    private fun allowedSigners(info: PackageInfo): Set<String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signing ->
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            }?.toList()?.digests()
        } else {
            info.signatures?.toList()?.digests()
        }

    private fun List<android.content.pm.Signature?>.digests(): Set<String>? = mapNotNull { signature ->
        signature?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    }.toSet().takeIf { it.isNotEmpty() }
}
