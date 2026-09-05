package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import java.io.File
import java.io.IOException
import okio.Path.Companion.toPath

/** Call once per process before the first pairing or connection. */
fun configureAdbIdentity(privateDirectory: File) {
    val fileStore = OkioFilePrivateKeyStore(File(privateDirectory, "adb/identity.pem").absolutePath.toPath())
    KadbCert.configure(
        store = object : KadbPrivateKeyStore by fileStore {
            override fun readPrivateKeyPem(): ByteArray? = fileStore.readPrivateKeyPem()?.also {
                // Kadb treats an empty file like a missing key even with auto-heal disabled.
                if (it.isEmpty()) throw IOException("Stored ADB identity is empty")
            }
        },
        // A damaged identity must not silently replace an already paired key.
        policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
    )
}
