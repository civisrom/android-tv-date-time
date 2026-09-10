package com.civisrom.tvtimefixer

import android.app.Application
import com.civisrom.tvtimefixer.adb.configureAdbIdentity
import com.civisrom.tvtimefixer.diagnostics.DiagnosticJournal
import com.civisrom.tvtimefixer.diagnostics.Operation
import com.civisrom.tvtimefixer.diagnostics.Outcome
import java.io.File

class TimeFixerApplication : Application() {
    val favorites by lazy { com.civisrom.tvtimefixer.data.FavoritesStore(File(noBackupFilesDir, "favorites.bin")) }
    lateinit var diagnostics: DiagnosticJournal
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = DiagnosticJournal(File(noBackupFilesDir, "diagnostics"), File(filesDir, "last-crash.txt"),
            monotonicClock = android.os.SystemClock::elapsedRealtime)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            diagnostics.recordCrash(error)
            previous?.uncaughtException(thread, error)
        }
        diagnostics.record(Operation.APP_START, Outcome.SUCCESS)
        configureAdbIdentity(noBackupFilesDir)
    }
}
