package com.tvtools.mousebridge;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Minimal client for the TV's own adb daemon (TCP, classic protocol), used to start the shell-side
 * Daemon after a reboot -- apps cannot start shell-user processes any other way.
 *
 * The first connection with a new key makes the TV show "Allow network debugging?"; once accepted
 * with "Always allow", later connections authenticate silently.
 */
final class AdbClient {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DER DigestInfo prefix for SHA-1: adbd verifies the token signature as RSA_sign(NID_sha1, token).
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final KeyPair keys;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    AdbClient(Context c) throws Exception {
        keys = loadOrCreateKeys(new File(c.getFilesDir(), "adbkey"), new File(c.getFilesDir(), "adbkey.pub"));
    }

    /** Runs a shell command on this device through adb and returns its output. */
    String shell(String host, int port, String command, int authTimeoutMs) throws Exception {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(authTimeoutMs);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();

            send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSignature = false, sentKey = false;
            while (true) {
                Msg m = read();
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message " + Integer.toHexString(m.cmd));
                if (!sentSignature) {
                    send(AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob());
                    sentKey = true; // the TV now shows the "Allow network debugging?" prompt
                } else {
                    throw new IOException("adb key rejected");
                }
            }

            socket.setSoTimeout(30000);
            send(OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read();
                if (m.cmd == OKAY) {
                    remote = m.arg0;
                } else if (m.cmd == WRTE) {
                    output.write(m.data);
                    send(OKAY, 1, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    send(CLSE, 1, m.arg0, null);
                    break;
                }
            }
            return output.toString("UTF-8");
        } finally {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------- protocol ----------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("bad adb length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------- keys ----------

    private byte[] sign(byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, digestInfo, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_PREFIX.length, token.length);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(keys.getPrivate());
        s.update(digestInfo);
        return s.sign();
    }

    /** The public key in adb's own format: base64(RSAPublicKey struct) + " name\0". */
    private byte[] publicKeyBlob() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        BigInteger t = n;
        for (int i = 0; i < 64; i++) { b.putInt(t.mod(r32).intValue()); t = t.shiftRight(32); }
        t = rr;
        for (int i = 0; i < 64; i++) { b.putInt(t.mod(r32).intValue()); t = t.shiftRight(32); }
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " mousebridge@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair loadOrCreateKeys(File priv, File pub) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath())));
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))), p);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }
}
