/*
 * Connection framing adapted from Kadb 2.1.3 AdbConnection.kt.
 * Copyright (c) 2024 Flyfish-Xu. Licensed under Apache License 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Changes: own the socket before AUTH/TLS, so cancellation can close it.
 */
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.civisrom.tvtimefixer.adb

import com.flyfishxu.kadb.cert.AndroidPubkey
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.core.AdbProtocol
import com.flyfishxu.kadb.core.AdbReader
import com.flyfishxu.kadb.core.AdbWriter
import com.flyfishxu.kadb.pair.SslUtils
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket
import okio.sink
import okio.source

/** Kadb's public close() cannot reach its socket until AUTH/TLS has finished. */
internal class AdbConnectSession(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
    private val ioTimeoutMs: Int,
) : Closeable {
    private val socket = Socket()
    @Volatile private var activeSocket: Socket = socket
    val isOpen: Boolean get() = socket.isConnected && !socket.isClosed

    fun connect(): KadbAdbClient {
        val keys = CertUtils.loadKeySet()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = ioTimeoutMs
        var reader = AdbReader(socket.source())
        var writer = AdbWriter(socket.sink())
        val advertised = AdbProtocol.connectFeatures().toSet()
        writer.writeConnect(AdbProtocol.connectPayload(advertised.toList()))
        var authKeyIndex = 0
        var publicKeySent = false
        var secured = false
        while (true) {
            val message = reader.readMessage()
            when (message.command) {
                AdbProtocol.CMD_STLS -> {
                    if (secured) throw IOException("Repeated ADB TLS negotiation")
                    if (android.os.Build.VERSION.SDK_INT < 29) throw AdbConnectionException(ConnectionError.WIRELESS_UNSUPPORTED)
                    writer.writeStls(AdbProtocol.A_STLS_VERSION)
                    val tls = SslUtils.getSslContext(keys).socketFactory.createSocket(socket, host, port, true) as SSLSocket
                    activeSocket = tls
                    tls.enabledProtocols = arrayOf("TLSv1.3")
                    tls.soTimeout = ioTimeoutMs
                    tls.startHandshake()
                    // The old buffers are empty at STLS. Closing them would close the shared socket.
                    reader = AdbReader(tls.source())
                    writer = AdbWriter(tls.sink())
                    secured = true
                }
                AdbProtocol.CMD_AUTH -> {
                    if (message.arg0 != AdbProtocol.AUTH_TYPE_TOKEN || message.payload.size != 20) {
                        throw IOException("Invalid ADB authentication challenge")
                    }
                    val key = keys.keyPairs.getOrNull(authKeyIndex)
                    if (key != null) {
                        authKeyIndex++
                        writer.writeAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, key.signPayload(message))
                    } else if (!publicKeySent) {
                        publicKeySent = true
                        writer.writeAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                            AndroidPubkey.encodeWithName(keys.defaultKeyPair.publicKey as RSAPublicKey, "TVTimeFixer"))
                    } else throw AdbConnectionException(ConnectionError.NOT_AUTHORIZED)
                }
                AdbProtocol.CMD_CNXN -> {
                    if (message.arg0 < AdbProtocol.A_VERSION_MIN || message.arg1 <= 0) throw IOException("Invalid ADB connection parameters")
                    val banner = message.payload.toString(Charsets.UTF_8).trimEnd('\u0000').split(':')
                    val features = banner.getOrNull(2).orEmpty().split(';')
                        .lastOrNull { it.startsWith("features=") }?.removePrefix("features=")
                        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
                    val version = minOf(message.arg0, AdbProtocol.A_VERSION)
                    val maxPayload = minOf(message.arg1, AdbProtocol.CONNECT_MAXDATA)
                    writer.updateProtocolVersion(version)
                    reader.setInboundMaxPayloadSize(maxPayload)
                    return KadbAdbClient(AdbConnection(reader, writer, this, features.intersect(advertised), version, maxPayload), this)
                }
                else -> throw IOException("Unexpected ADB handshake packet")
            }
        }
    }

    override fun close() {
        // Closing TCP first interrupts both plain reads and a blocked Conscrypt handshake.
        runCatching { socket.close() }
        if (activeSocket !== socket) runCatching { activeSocket.close() }
    }
}
