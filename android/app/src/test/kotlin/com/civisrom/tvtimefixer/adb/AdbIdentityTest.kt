package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertException
import java.io.File
import java.security.cert.CertificateFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun resetStore() { KadbCert.configure(InMemoryPrivateKeyStore()) }

    @Test fun `pairing key survives reinitialization from private storage`() {
        configureAdbIdentity(temporary.root)
        val first = publicKey()
        assertTrue(File(temporary.root, "adb/identity.pem").isFile)
        configureAdbIdentity(temporary.root)
        assertArrayEquals(first, publicKey())
    }

    // A certificate is reissued on reload; ADB authorization follows the RSA key.
    private fun publicKey(): ByteArray = CertificateFactory.getInstance("X.509")
        .generateCertificate(KadbCert.ensureReady().certificatePem.inputStream()).publicKey.encoded

    @Test fun `corrupted identity is not silently replaced`() {
        val directory = temporary.newFolder("adb")
        val key = File(directory, "identity.pem")
        key.writeText("invalid fixture key")
        configureAdbIdentity(temporary.root)
        assertThrows(KadbCertException::class.java) { KadbCert.ensureReady() }
        assertEquals("invalid fixture key", key.readText())
    }

    @Test fun `empty existing identity is not silently replaced`() {
        val key = File(temporary.newFolder("adb"), "identity.pem")
        key.writeText("")
        configureAdbIdentity(temporary.root)
        assertThrows(KadbCertException::class.java) { KadbCert.ensureReady() }
        assertEquals(0L, key.length())
    }
}
