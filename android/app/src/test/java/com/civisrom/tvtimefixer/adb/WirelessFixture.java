package com.civisrom.tvtimefixer.adb;

import com.flyfishxu.kadb.cert.*;
import com.flyfish233.crypto.spake2.*;
import org.conscrypt.Conscrypt;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import javax.net.ssl.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

// Local protocol fixture, not an Android emulator. All identities are ephemeral.
public class WirelessFixture {
    static final int CNXN = 0x4e584e43, STLS = 0x534c5453;
    static final int OPEN = 0x4e45504f, OKAY = 0x59414b4f;
    static final int WRTE = 0x45545257, CLSE = 0x45534c43;
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] join(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
    static SSLContext serverContext() throws Exception {
        AdbKeyPair identity = CertUtils.INSTANCE.loadKeyPair();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null);
        store.setKeyEntry("fixture", identity.getPrivateKey(), new char[0],
            new java.security.cert.Certificate[]{identity.getCertificate()});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider());
        ctx.init(km.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String auth) {}
            public void checkServerTrusted(X509Certificate[] chain, String auth) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return ctx;
    }
    static SSLSocket tls(SSLContext ctx, Socket socket) throws Exception {
        SSLSocket ssl = (SSLSocket)ctx.getSocketFactory().createSocket(
            socket, "127.0.0.1", socket.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
        ssl.setNeedClientAuth(true);
        ssl.setSoTimeout(5000);
        ssl.startHandshake();
        return ssl;
    }
    static byte[] readPair(DataInputStream in, int expected) throws Exception {
        if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != expected)
            throw new IOException("Incorrect pairing header");
        int length = in.readInt();
        if (length <= 0 || length > 16384) throw new IOException("Incorrect pairing length");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
    static void writePair(DataOutputStream out, int type, byte[] data) throws Exception {
        out.writeByte(1); out.writeByte(type); out.writeInt(data.length); out.write(data); out.flush();
    }
    static byte[] aes(boolean encrypt, byte[] key, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, new byte[12]));
        return cipher.doFinal(input);
    }
    static void pairServer(SSLContext ctx, ServerSocket listener, boolean corrupt) throws Exception {
        try (Socket raw = listener.accept(); SSLSocket ssl = tls(ctx, raw)) {
            byte[] password = join(bytes("123456"),
                Conscrypt.exportKeyingMaterial(ssl, "adb-label\0", null, 64));
            try (Spake2Context spake = new Spake2Context(Spake2Role.Bob,
                    bytes("adb pair server\0"), bytes("adb pair client\0"))) {
                byte[] message = spake.generateMessage(password);
                DataInputStream in = new DataInputStream(ssl.getInputStream());
                DataOutputStream out = new DataOutputStream(ssl.getOutputStream());
                byte[] theirs = readPair(in, 0);
                writePair(out, 0, message);
                byte[] key = new byte[16];
                HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
                hkdf.init(new HKDFParameters(spake.processMessage(theirs), null,
                    bytes("adb pairing_auth aes-128-gcm key")));
                hkdf.generateBytes(key, 0, key.length);
                byte[] peer = aes(false, key, readPair(in, 1));
                if (peer.length != 8192 || peer[0] != 0) throw new IOException("Incorrect RSA PeerInfo");
                int end = 1;
                while (end < peer.length && peer[end] != 0) end++;
                String encoded = new String(peer, 1, end - 1, StandardCharsets.UTF_8).split(" ")[0];
                byte[] adbKey = Base64.getDecoder().decode(encoded);
                if (adbKey.length != 524) throw new IOException("Incorrect ADB RSA encoding");
                ByteBuffer keyData = ByteBuffer.wrap(adbKey).order(ByteOrder.LITTLE_ENDIAN);
                if (keyData.getInt() != 64) throw new IOException("Incorrect RSA word count");
                keyData.getInt(); // Montgomery n0inv; the peer key is compared independently below.
                byte[] modulus = new byte[256]; keyData.get(modulus);
                for (int i = 0; i < 128; i++) {
                    byte swap = modulus[i]; modulus[i] = modulus[255 - i]; modulus[255 - i] = swap;
                }
                var certificateKey = (java.security.interfaces.RSAPublicKey)
                    ssl.getSession().getPeerCertificates()[0].getPublicKey();
                if (!certificateKey.getModulus().equals(new java.math.BigInteger(1, modulus)) ||
                    certificateKey.getPublicExponent().intValue() != keyData.getInt(520))
                    throw new IOException("Pairing key differs from TLS identity");
                byte[] guid = new byte[8192];
                guid[0] = 1;
                byte[] text = bytes("adb-audit-fixture\0");
                System.arraycopy(text, 0, guid, 1, text.length);
                byte[] encrypted = aes(true, key, guid);
                if (corrupt) encrypted[encrypted.length - 1] ^= 1;
                writePair(out, 1, encrypted);
                System.out.println("pair-server: verified SPAKE2, decrypted RSA PeerInfo; corrupt=" + corrupt);
            }
        }
    }
    record Packet(int command, int arg0, int arg1, byte[] payload) {}
    static Packet readAdb(InputStream in) throws Exception {
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[24]; data.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), a0 = b.getInt(), a1 = b.getInt(), len = b.getInt();
        b.getInt();
        if (len < 0 || len > 1048576 || b.getInt() != ~command) throw new IOException("ADB header");
        byte[] payload = new byte[len]; data.readFully(payload);
        return new Packet(command, a0, a1, payload);
    }
    static void writeAdb(OutputStream out, int cmd, int a0, int a1, byte[] payload) throws Exception {
        int sum = 0; for (byte value : payload) sum += value & 255;
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(payload.length).putInt(sum).putInt(~cmd).array());
        out.write(payload); out.flush();
    }
    static byte[] shellPacket(int type, byte[] data) {
        return join(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte)type).putInt(data.length).array(), data);
    }
    static void connectServer(SSLContext ctx, ServerSocket listener) throws Exception {
        try (Socket raw = listener.accept()) {
            raw.setSoTimeout(5000);
            if (readAdb(raw.getInputStream()).command != CNXN) throw new IOException("Expected CNXN");
            writeAdb(raw.getOutputStream(), STLS, 0x01000000, 0, new byte[0]);
            if (readAdb(raw.getInputStream()).command != STLS) throw new IOException("Expected STLS");
            try (SSLSocket ssl = tls(ctx, raw)) {
                // Verify that connect presents the same public key used by pairing in this process.
                if (!Arrays.equals(ssl.getSession().getPeerCertificates()[0].getPublicKey().getEncoded(),
                    CertUtils.INSTANCE.loadKeyPair().getPublicKey().getEncoded())) throw new IOException("Identity changed");
                writeAdb(ssl.getOutputStream(), CNXN, 0x01000001, 1048576,
                    bytes("device::ro.product.name=audit;features=shell_v2"));
                Packet open = readAdb(ssl.getInputStream());
                if (open.command != OPEN) throw new IOException("Expected OPEN");
                if (!new String(open.payload, StandardCharsets.UTF_8).startsWith("shell,v2,raw:"))
                    throw new IOException("Client did not negotiate shell_v2");
                int local = open.arg0, remote = 1;
                writeAdb(ssl.getOutputStream(), OKAY, remote, local, new byte[0]);
                byte[] output = join(shellPacket(1, bytes("tvtimefixer\n")),
                    shellPacket(3, new byte[]{0}));
                writeAdb(ssl.getOutputStream(), WRTE, remote, local, output);
                readAdb(ssl.getInputStream());
                writeAdb(ssl.getOutputStream(), CLSE, remote, local, new byte[0]);
                System.out.println("connect-server: TLSv1.3, matching RSA identity, shell_v2 reply sent");
            }
        }
    }
    static ServerSocket listener() throws Exception {
        return new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
    }
}
