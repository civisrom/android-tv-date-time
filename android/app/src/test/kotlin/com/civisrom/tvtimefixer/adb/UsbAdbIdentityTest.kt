@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class UsbAdbIdentityTest {
    @After fun reset() { KadbCert.configure(store = InMemoryPrivateKeyStore()) }

    @Test fun `USB signs the raw ADB digest using the same identity as network ADB`() {
        KadbCert.configure(store = InMemoryPrivateKeyStore())
        val key = CertUtils.loadKeyPair()
        val challenge = ByteArray(20) { it.toByte() }
        val identity = KadbUsbIdentity()
        val signed = identity.sign(challenge)
        assertEquals(256, signed.size)
        val decoded = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, key.publicKey)
            doFinal(signed)
        }
        assertEquals(35, decoded.size)
        assertArrayEquals(challenge, decoded.copyOfRange(15, 35))
        assertEquals(0, identity.publicKey().last().toInt())
        assertArrayEquals(identity.publicKey(), KadbUsbIdentity().publicKey())
    }
}
