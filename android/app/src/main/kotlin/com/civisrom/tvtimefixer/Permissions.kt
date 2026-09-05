package com.civisrom.tvtimefixer

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/**
 * NsdManager does not require NEARBY_WIFI_DEVICES on Android 13-16.
 * Android 17 local-network permission is enforced only for target SDK 37+.
 *
 * Версия передаётся параметром, а не читается из [Build.VERSION] внутри:
 * так набор проверяется на JVM сразу для всех интересных версий.
 */
// Имена разрешений — строковые константы, компилятор подставляет их значения:
// на старой системе строка просто никому не нужна, а не ломает вызов
@SuppressLint("InlinedApi")
fun discoveryPermissions(sdkInt: Int, targetSdkInt: Int): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN && targetSdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}
