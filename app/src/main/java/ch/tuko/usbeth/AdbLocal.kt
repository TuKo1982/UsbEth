// UsbEth - Copyright (C) 2026 Renaud Schweingruber - SPDX-License-Identifier: GPL-3.0-or-later
package ch.tuko.usbeth

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Minimal adb client for the TV's own adb daemon (network debugging, 127.0.0.1:5555): runs one
 * shell command as the shell user. Used to start [BaseNet], which needs shell permissions.
 *
 * The first connection shows Android's "Allow debugging?" dialog for UsbEth's key (kept in the
 * app's private storage); with "Always allow" it is not asked again.
 */
class AdbLocal(private val ctx: Context, private val log: (String) -> Unit) {

    class AdbException(msg: String) : Exception(msg)

    private companion object {
        const val CNXN = 0x4e584e43; const val AUTH = 0x48545541; const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f; const val CLSE = 0x45534c43; const val WRTE = 0x45545257
        const val VERSION = 0x01000001; const val MAXDATA = 256 * 1024
        const val AUTH_TOKEN = 1; const val AUTH_SIGNATURE = 2; const val AUTH_RSAPUBLICKEY = 3
        // DER DigestInfo prefix of SHA-1: adbd checks the token as an already hashed SHA-1 value
        val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14)
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Runs [cmd] with sh -c as the shell user and returns its output. [authWaitMs] covers the dialog. */
    fun shell(cmd: String, port: Int = 5555, authWaitMs: Int = 120_000): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            val inp = DataInputStream(s.getInputStream().buffered())
            val out = s.getOutputStream()
            send(out, CNXN, VERSION, MAXDATA, "host::\u0000".toByteArray())

            val keys = keyPair()
            var signed = false
            var offered = false
            while (true) {
                val m = read(inp)
                when {
                    m.cmd == CNXN -> break
                    m.cmd == AUTH && m.arg0 == AUTH_TOKEN && !signed -> {
                        signed = true
                        send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                    }
                    m.cmd == AUTH && m.arg0 == AUTH_TOKEN && offered -> throw AdbException("debugging key refused")
                    m.cmd == AUTH && m.arg0 == AUTH_TOKEN -> {
                        offered = true
                        // key not known yet: offer it, adbd shows the dialog and answers CNXN on "Allow"
                        log("adb: allow UsbEth's debugging key on the TV screen (tick \"Always allow\")")
                        send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys))
                        s.soTimeout = authWaitMs
                    }
                    else -> throw AdbException("unexpected adb message ${Integer.toHexString(m.cmd)}")
                }
            }
            s.soTimeout = 15_000

            val localId = 1
            send(out, OPEN, localId, 0, "shell:$cmd\u0000".toByteArray())
            val result = ByteArrayOutputStream()
            while (true) {
                val m = read(inp)
                when (m.cmd) {
                    OKAY -> {}
                    WRTE -> { result.write(m.data); send(out, OKAY, localId, m.arg0, ByteArray(0)) }
                    CLSE -> { send(out, CLSE, localId, m.arg0, ByteArray(0)); return String(result.toByteArray()) }
                    else -> throw AdbException("unexpected adb message ${Integer.toHexString(m.cmd)}")
                }
            }
        }
    }

    private fun send(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
        b.putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
        out.write(b.array()); out.flush()
    }

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        if (len < 0 || len > MAXDATA) throw AdbException("bad adb message length $len")
        val d = ByteArray(len); inp.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    private fun keyPair(): KeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "key"); val pub = File(dir, "key.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.isFile && pub.isFile) try {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        } catch (_: Exception) {}
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded); pub.writeBytes(kp.public.encoded)
        return kp
    }

    private fun sign(kp: KeyPair, token: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, kp.private)
            doFinal(SHA1_PREFIX + token)
        }

    /** adb's public key format: mincrypt RSAPublicKey (2048 bits) in base64, then a name. */
    private fun publicKeyBlob(kp: KeyPair): ByteArray {
        val pk = kp.public as RSAPublicKey
        val words = 64
        val n = pk.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv)
        putWords(b, n, words); putWords(b, rr, words)
        b.putInt(pk.publicExponent.toInt())
        val text = Base64.getEncoder().encodeToString(b.array()) + " UsbEth@tv\u0000"
        return text.toByteArray()
    }

    private fun putWords(b: ByteBuffer, v: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xffffffffL)
        var x = v
        repeat(words) { b.putInt(x.and(mask).toInt()); x = x.shiftRight(32) }
    }
}
