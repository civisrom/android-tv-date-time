package com.civisrom.tvtimefixer

import android.app.Application
import com.civisrom.tvtimefixer.adb.configureAdbIdentity

class TimeFixerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureAdbIdentity(noBackupFilesDir)
    }
}
