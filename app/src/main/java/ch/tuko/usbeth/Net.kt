/*
 * UsbEth - USB Ethernet for Android TV without kernel drivers
 * Copyright (C) 2026 Renaud Schweingruber <renaud.schweingruber@protonmail.com>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details: <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package ch.tuko.usbeth

/** Small byte / IPv4 / checksum helpers. */
object Net {
    const val ETH_HDR = 14
    const val ETHERTYPE_IP = 0x0800
    const val ETHERTYPE_ARP = 0x0806
    const val ETHERTYPE_IPV6 = 0x86DD

    val BROADCAST_MAC = byteArrayOf(-1, -1, -1, -1, -1, -1)

    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)
    fun u32(b: ByteArray, o: Int) =
        (u8(b, o) shl 24) or (u8(b, o + 1) shl 16) or (u8(b, o + 2) shl 8) or u8(b, o + 3)

    fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }

    /** Internet checksum (RFC 1071) over [off, off+len). */
    fun checksum(b: ByteArray, off: Int, len: Int, seed: Long = 0): Int {
        var s = seed
        var i = off
        var n = len
        while (n > 1) { s += u16(b, i); i += 2; n -= 2 }
        if (n == 1) s += u8(b, i) shl 8
        while ((s shr 16) != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    fun ipToInt(ip: ByteArray, off: Int = 0) = u32(ip, off)
    fun intToIp(v: Int): ByteArray { val b = ByteArray(4); put32(b, 0, v); return b }
    fun ipStr(ip: ByteArray, off: Int = 0) =
        "${u8(ip, off)}.${u8(ip, off + 1)}.${u8(ip, off + 2)}.${u8(ip, off + 3)}"
    fun ipStr(v: Int) = ipStr(intToIp(v))
    fun macStr(m: ByteArray, off: Int = 0) =
        (0 until 6).joinToString(":") { "%02x".format(u8(m, off + it)) }

    fun prefixLen(mask: Int): Int { var m = mask; var n = 0; while (m != 0) { n++; m = m shl 1 }; return n }

    fun isMulticast(ip: Int) = (ip ushr 28) == 0xE
    fun multicastMac(ip: Int): ByteArray = byteArrayOf(
        0x01, 0x00, 0x5e.toByte(), ((ip shr 16) and 0x7f).toByte(), (ip shr 8).toByte(), ip.toByte()
    )

    /** Writes an Ethernet header at offset 0. */
    fun ethHeader(dst: ByteArray, src: ByteArray, type: Int, out: ByteArray) {
        System.arraycopy(dst, 0, out, 0, 6)
        System.arraycopy(src, 0, out, 6, 6)
        put16(out, 12, type)
    }
}
