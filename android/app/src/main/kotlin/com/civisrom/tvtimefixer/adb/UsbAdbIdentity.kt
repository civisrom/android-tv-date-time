@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.CertUtils
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/** Та же постоянная ADB identity, что у TCP/pairing; не ключ подписи APK. */
internal class KadbUsbIdentity : UsbAdbIdentity {
    private val keyPair by lazy { CertUtils.loadKeyPair() }

    override fun sign(token: ByteArray): ByteArray {
        require(token.size == 20)
        // AUTH token — уже SHA-1 digest. SHA1withRSA захешировал бы его второй раз.
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
            0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, keyPair.privateKey)
            doFinal(digestInfo + token)
        }
    }

    override fun publicKey(): ByteArray =
        AndroidPubkey.encodeWithName(keyPair.publicKey as RSAPublicKey, "tvtimefixer")
}
