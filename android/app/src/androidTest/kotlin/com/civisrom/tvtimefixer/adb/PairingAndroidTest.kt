@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.civisrom.tvtimefixer.adb

import android.os.Build
import com.civisrom.tvtimefixer.data.DeviceAddress
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Настоящий системный TLS provider APK. Все сокеты и код относятся к локальному тесту. */
class PairingAndroidTest {
    @Test fun pairing_uses_the_installed_Android_TLS_exporter_and_crypto() {
        if (Build.VERSION.SDK_INT < 29) {
            val socket = javax.net.ssl.SSLContext.getDefault().socketFactory.createSocket() as SSLSocket
            socket.use {
                val error = assertThrows(AdbConnectionException::class.java) { exportAndroidPairingKey(it) }
                assertEquals(ConnectionError.WIRELESS_UNSUPPORTED, error.reason)
            }
            return
        }
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 8_000
            val server = executor.submit<Unit> {
                listener.accept().use { raw ->
                    val context = SslUtils.getSslContext(CertUtils.loadKeyPair())
                    (context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket).use { tls ->
                        tls.useClientMode = false
                        tls.needClientAuth = true
                        tls.enabledProtocols = arrayOf("TLSv1.3")
                        tls.soTimeout = 8_000
                        tls.startHandshake()
                        val material = exportAndroidPairingKey(tls)
                        assertEquals(64, material.size)
                        Spake2Context(Spake2Role.Bob, bytes("adb pair server\u0000"), bytes("adb pair client\u0000")).use { spake ->
                            val message = spake.generateMessage(bytes("123456") + material)
                            material.fill(0)
                            val input = DataInputStream(tls.inputStream)
                            val output = DataOutputStream(tls.outputStream)
                            val peerMessage = readPacket(input, 0)
                            writePacket(output, 0, message)
                            val key = ByteArray(16)
                            HKDFBytesGenerator(SHA256Digest()).apply {
                                init(HKDFParameters(spake.processMessage(peerMessage), null, bytes("adb pairing_auth aes-128-gcm key")))
                                generateBytes(key, 0, key.size)
                            }
                            val peer = aes(false, key, readPacket(input, 1))
                            assertEquals(8192, peer.size)
                            assertEquals(0, peer[0].toInt())
                            val guid = ByteArray(8192).also { it[0] = 1 }
                            writePacket(output, 1, aes(true, key, guid))
                            key.fill(0)
                        }
                    }
                }
            }
            try {
                runBlocking { PairingClient(5_000, 8_000, 15_000, ::exportAndroidPairingKey)
                    .pair(DeviceAddress("127.0.0.1", listener.localPort), "123456") }
                server.get(10, TimeUnit.SECONDS)
            } finally {
                listener.close()
                server.cancel(true)
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        assertEquals(1, input.readUnsignedByte()); assertEquals(type, input.readUnsignedByte())
        val size = input.readInt(); require(size in 1..16384)
        return ByteArray(size).also(input::readFully)
    }
    private fun writePacket(output: DataOutputStream, type: Int, data: ByteArray) {
        output.writeByte(1); output.writeByte(type); output.writeInt(data.size); output.write(data); output.flush()
    }
    private fun aes(encrypt: Boolean, key: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
        doFinal(data)
    }
}
