package com.civisrom.tvtimefixer.adb

import android.annotation.SuppressLint
import android.os.Build
import java.lang.reflect.InvocationTargetException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Matches the Android Conscrypt exporter used by Kadb 2.1.3. */
@SuppressLint("PrivateApi")
internal fun exportAndroidPairingKey(socket: SSLSocket): ByteArray {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED)
    }
    try {
        HiddenApiBypass.addHiddenApiExemptions("Lcom/android/org/conscrypt/Conscrypt;")
        val method = Class.forName("com.android.org.conscrypt.Conscrypt").getMethod(
            "exportKeyingMaterial", SSLSocket::class.java, String::class.java,
            ByteArray::class.java, Int::class.javaPrimitiveType,
        )
        return method.invoke(null, socket, "adb-label\u0000", null, 64) as? ByteArray
            ?: throw SSLException("TLS exporter did not return key material")
    } catch (e: InvocationTargetException) {
        throw SSLException("TLS exporter failed", e.cause ?: e)
    } catch (e: ReflectiveOperationException) {
        throw SSLException("Android TLS exporter is unavailable", e)
    } catch (e: SecurityException) {
        throw SSLException("Android TLS exporter access is denied", e)
    }
}
