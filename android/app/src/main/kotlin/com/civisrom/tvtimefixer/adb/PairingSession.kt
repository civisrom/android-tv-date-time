/*
 * Pairing framing adapted from Kadb 2.1.3 PairingConnectionCtx.kt.
 * Copyright (c) 2024 Flyfish-Xu. Licensed under Apache License 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Changes: bounded/cancellable socket ownership and typed failure reporting.
 */
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.pair.PairingAuthCtx
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfishxu.kadb.pair.createAlice
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

internal class PairingRejectedException : IOException("Pairing authentication failed")
internal class PairingProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Owns the sockets missing from Kadb's public pairing API. Cryptography stays
 * in the pinned Kadb dependency; its internal ABI is covered by protocol tests.
 */
internal class PairingSession(
    private val host: String,
    private val port: Int,
    private val code: String,
    private val connectTimeoutMs: Int,
    private val socketTimeoutMs: Int,
    private val exportKey: (SSLSocket) -> ByteArray,
) : Closeable {
    private val socket = Socket()

    fun start() {
        val key = CertUtils.loadKeyPair()
        val context = SslUtils.getSslContext(key)
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = socketTimeoutMs
        socket.tcpNoDelay = true
        (context.socketFactory.createSocket(socket, host, port, true) as SSLSocket).use { tls ->
            tls.soTimeout = socketTimeoutMs
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.startHandshake()
            val material = exportKey(tls)
            if (material.size != 64) throw SSLException("Invalid TLS exporter result")
            val password = code.toByteArray(Charsets.UTF_8) + material
            val auth = try {
                PairingAuthCtx.createAlice(password)
                    ?: throw PairingProtocolException("Cannot initialize SPAKE2")
            } finally {
                password.fill(0)
                material.fill(0)
            }
            try {
                val input = DataInputStream(tls.inputStream)
                val output = DataOutputStream(tls.outputStream)
                writePacket(output, SPAKE2, auth.msg)
                if (!auth.initCipher(readPacket(input, SPAKE2))) throw PairingRejectedException()

                val publicKey = AndroidPubkey.encodeWithName(key.publicKey as RSAPublicKey, "TVTimeFixer")
                val info = ByteArray(PEER_INFO_SIZE)
                publicKey.copyInto(info, destinationOffset = 1)
                val encrypted = auth.encrypt(info) ?: throw PairingProtocolException("Cannot encrypt peer info")
                writePacket(output, PEER_INFO, encrypted)
                val decrypted = auth.decrypt(readPacket(input, PEER_INFO)) ?: throw PairingRejectedException()
                if (decrypted.size != PEER_INFO_SIZE || decrypted[0].toInt() != DEVICE_GUID) {
                    throw PairingProtocolException("Invalid device peer info")
                }
            } catch (e: EOFException) {
                throw PairingProtocolException("Peer closed the pairing exchange; request a fresh code", e)
            } finally {
                auth.destroy()
            }
        }
    }

    // Closing the underlying TCP socket also interrupts a blocked TLS handshake/read.
    override fun close() {
        runCatching { socket.close() }
    }

    private fun readPacket(input: DataInputStream, expectedType: Int): ByteArray {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (version != 1 || type != expectedType || length !in 1..(2 * PEER_INFO_SIZE)) {
            throw PairingProtocolException("Invalid pairing packet header")
        }
        return ByteArray(length).also { input.readFully(it) }
    }

    private fun writePacket(output: DataOutputStream, type: Int, data: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }

    private companion object {
        const val SPAKE2 = 0
        const val PEER_INFO = 1
        const val DEVICE_GUID = 1
        const val PEER_INFO_SIZE = 8192
    }
}
