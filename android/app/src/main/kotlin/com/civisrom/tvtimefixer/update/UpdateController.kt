package com.civisrom.tvtimefixer.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, PERMISSION_REQUIRED, INSTALLING, FAILED }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ReleaseUpdate? = null,
    val latestVersion: String = "",
    val downloaded: Long = 0,
    /** APK скачан и проверен: повторная установка не требует новой загрузки. */
    val apkReady: Boolean = false,
    val error: UpdateError? = null,
    val settingsUnavailable: Boolean = false,
) {
    /**
     * Ожидание подтверждения установки сюда не входит: старые версии Android
     * не сообщают, что окно установщика закрыли кнопкой «Назад», и тогда
     * повторить установку было бы нечем.
     */
    val busy: Boolean get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
}

interface UpdateActions {
    fun check()
    fun downloadAndInstall()
    fun cancelDownload()
    fun install()
    fun openInstallSettings()
}

/**
 * Проверка, загрузка и установка новой версии из GitHub Releases.
 *
 * Живёт вместе с Activity: загрузка прерывается при её уничтожении, а
 * результат установки принимает приёмник, зарегистрированный только на это время.
 */
class UpdateController(
    private val activity: ComponentActivity,
    private val client: UpdateClient,
    private val currentVersion: String,
) : UpdateActions {
    private val installer = UpdateInstaller(activity)
    private val directory = File(activity.cacheDir, "updates")
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var job: Job? = null
    private var apk: File? = null
    private var sessionId: Int? = null
    private var pendingConfirmation: Intent? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (id != sessionId) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Приёмник не экспортирован, сюда пишет только PendingIntent нашей сессии.
                pendingConfirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                launchConfirmation()
                return
            }
            sessionId = null
            val error = installStatusError(status)
            mutableState.value = if (error == null) UpdateState(UpdatePhase.IDLE)
                else mutableState.value.copy(phase = UpdatePhase.FAILED, error = error)
        }
    }

    fun register() {
        // Прошлый процесс мог оставить недокачанный или уже установленный APK.
        activity.lifecycleScope.launch(Dispatchers.IO) { if (apk == null) directory.deleteRecursively() }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(ACTION_UPDATE_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun unregister() {
        job?.cancel()
        if (registered) activity.unregisterReceiver(receiver)
        registered = false
    }

    /** При возврате на экран: показать отложенное окно подтверждения или продолжить после выдачи разрешения. */
    fun onStart() {
        launchConfirmation()
        if (state.value.phase == UpdatePhase.PERMISSION_REQUIRED && installer.canInstall()) install()
    }

    override fun check() {
        if (state.value.busy) return
        mutableState.value = UpdateState(UpdatePhase.CHECKING)
        job = activity.lifecycleScope.launch {
            mutableState.value = try {
                val release = client.latestRelease()
                val current = AppVersion.parse(currentVersion) ?: AppVersion(0, 0, 0, prerelease = true)
                if (release.version > current) UpdateState(UpdatePhase.AVAILABLE, release, release.version.toString())
                else UpdateState(UpdatePhase.UP_TO_DATE, latestVersion = release.version.toString())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                UpdateState(UpdatePhase.FAILED, error = error.updateError(UpdateError.NETWORK))
            }
        }
    }

    override fun downloadAndInstall() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, release, release.version.toString())
        job = activity.lifecycleScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    directory.deleteRecursively()
                    if (!directory.mkdirs()) throw UpdateException(UpdateError.STORAGE)
                    File(directory, release.apkName)
                }
                var shown = -1L
                client.download(release, target) { bytes ->
                    // Не чаще одного обновления экрана на процент.
                    val step = bytes * 100 / release.apkSize
                    if (step != shown) {
                        shown = step
                        mutableState.update { it.copy(downloaded = bytes) }
                    }
                }
                withContext(Dispatchers.IO) {
                    verifyArchive(installer.installedIdentity(), installer.archiveIdentity(target))?.let {
                        target.delete()
                        throw UpdateException(it)
                    }
                }
                apk = target
                mutableState.value = mutableState.value.copy(downloaded = release.apkSize, apkReady = true)
                startInstall()
            } catch (error: CancellationException) {
                mutableState.value = UpdateState(UpdatePhase.AVAILABLE, release, release.version.toString())
                throw error
            } catch (error: Exception) {
                mutableState.value = UpdateState(UpdatePhase.FAILED, release, release.version.toString(),
                    error = error.updateError(UpdateError.NETWORK))
            }
        }
    }

    override fun cancelDownload() {
        if (state.value.phase == UpdatePhase.DOWNLOADING) job?.cancel()
    }

    override fun install() {
        if (state.value.busy || !state.value.apkReady) return
        startInstall()
    }

    private fun startInstall() {
        val file = apk
        if (file == null || !file.isFile) {
            apk = null
            mutableState.value = mutableState.value.copy(phase = UpdatePhase.FAILED, apkReady = false, error = UpdateError.STORAGE)
            return
        }
        if (!installer.canInstall()) {
            mutableState.value = mutableState.value.copy(phase = UpdatePhase.PERMISSION_REQUIRED, error = null)
            return
        }
        mutableState.value = mutableState.value.copy(phase = UpdatePhase.INSTALLING, error = null)
        job = activity.lifecycleScope.launch {
            try {
                sessionId?.let(installer::abandon)
                val id = withContext(Dispatchers.IO) { installer.createSession(file) }
                sessionId = id
                withContext(Dispatchers.IO) { installer.commit(id, file) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(phase = UpdatePhase.FAILED,
                    error = error.updateError(UpdateError.INSTALL_FAILED))
            }
        }
    }

    override fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // На части телевизоров этого экрана нет: подсказка ведёт в общие настройки.
            mutableState.value = mutableState.value.copy(settingsUnavailable = true)
        }
    }

    private fun launchConfirmation() {
        val intent = pendingConfirmation ?: return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        pendingConfirmation = null
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            sessionId?.let(installer::abandon)
            sessionId = null
            mutableState.value = mutableState.value.copy(phase = UpdatePhase.FAILED, error = UpdateError.INSTALL_BLOCKED)
        }
    }

    private fun Exception.updateError(fallback: UpdateError): UpdateError = (this as? UpdateException)?.error ?: fallback
}
