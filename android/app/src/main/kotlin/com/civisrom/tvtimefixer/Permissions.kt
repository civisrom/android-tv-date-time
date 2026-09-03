package com.civisrom.tvtimefixer

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/**
 * Разрешения, без которых обнаружение устройств молча не работает.
 *
 * С Android 13 системный NsdManager, на котором построен mDNS, требует
 * NEARBY_WIFI_DEVICES; Android 17 закрывает сокеты в локальной сети без
 * ACCESS_LOCAL_NETWORK, а всё приложение только в неё и ходит.
 *
 * Версия передаётся параметром, а не читается из [Build.VERSION] внутри:
 * так набор проверяется на JVM сразу для всех интересных версий.
 */
// Имена разрешений — строковые константы, компилятор подставляет их значения:
// на старой системе строка просто никому не нужна, а не ломает вызов
@SuppressLint("InlinedApi")
fun discoveryPermissions(sdkInt: Int): List<String> = buildList {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
}
