@file:SuppressLint("PrivateApi")

package com.civisrom.tvtimefixer.adb

import android.annotation.SuppressLint
import android.os.Build
import java.lang.reflect.InvocationTargetException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import org.lsposed.hiddenapibypass.HiddenApiBypass

// HiddenApiBypass mutates a shared, unsynchronized set. Initialize it once,
// even when two pairing sessions reach the exporter at the same time.
private val androidPairingExporter by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED)
    }
    if (!HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/Conscrypt;")) {
        throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED)
    }
    Class.forName("com.android.org.conscrypt.Conscrypt").getMethod(
        "exportKeyingMaterial", SSLSocket::class.java, String::class.java,
        ByteArray::class.java, Int::class.javaPrimitiveType,
    )
}

/** Matches the Android Conscrypt exporter used by Kadb 2.1.3. */
internal fun exportAndroidPairingKey(socket: SSLSocket): ByteArray {
    try {
        return androidPairingExporter.invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray
            ?: throw SSLException("TLS exporter did not return key material")
    } catch (e: InvocationTargetException) {
        throw SSLException("TLS exporter failed", e.cause ?: e)
    } catch (e: ReflectiveOperationException) {
        throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED, e)
    } catch (e: SecurityException) {
        throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED, e)
    }
}
