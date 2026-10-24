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

import kotlin.random.Random

/** Minimal DHCPv4 client that builds its Ethernet/IP/UDP frames by hand. */
class Dhcp(private val mac: ByteArray, private val hostname: String, private val log: (String) -> Unit) {

    class Lease(
        val ip: Int, val mask: Int, val router: Int,
        val dns: List<Int>, val server: Int, val serverMac: ByteArray?, val leaseSec: Long
    ) {
        val prefix get() = Net.prefixLen(mask)
        override fun toString() =
            "ip=${Net.ipStr(ip)}/$prefix gw=${Net.ipStr(router)} dns=${dns.joinToString { Net.ipStr(it) }} " +
                    "srv=${Net.ipStr(server)} lease=${leaseSec}s"
    }

    @Volatile private var xid = Random.nextInt()
    @Volatile var offer: Lease? = null
    @Volatile var ack: Lease? = null
    @Volatile var nak = false

    fun newTransaction() { xid = Random.nextInt(); offer = null; ack = null; nak = false }

    fun discover(): ByteArray = build(1, 0, null, 0, 0, broadcast = true)

    /** REQUEST after OFFER (broadcast, options 50 + 54). */
    fun request(o: Lease): ByteArray = build(3, 0, null, o.ip, o.server, broadcast = true)

    /** Renewal: unicast to the server, ciaddr = our IP. */
    fun renew(l: Lease): ByteArray = build(3, l.ip, l.serverMac, 0, 0, broadcast = false, dstIp = l.server)

    /** Returns true if the frame was a DHCP packet for us (consumed). */
    fun onFrame(f: ByteArray, len: Int): Boolean {
        if (len < 14 + 20 + 8 + 240) return false
        if (Net.u16(f, 12) != Net.ETHERTYPE_IP) return false
        val ihl = (Net.u8(f, 14) and 0x0f) * 4
        if (Net.u8(f, 14 + 9) != 17) return false
        val udp = 14 + ihl
        if (Net.u16(f, udp + 2) != 68) return false
        val b = udp + 8
        if (Net.u8(f, b) != 2 || Net.u32(f, b + 4) != xid) return false
        if (Net.u32(f, b + 236) != 0x63825363) return false

        var msgType = 0; var mask = 0; var router = 0; var server = 0; var lease = 86400L
        val dns = ArrayList<Int>()
        var i = b + 240
        while (i < len) {
            val opt = Net.u8(f, i)
            if (opt == 0xFF) break
            if (opt == 0) { i++; continue }
            val l = Net.u8(f, i + 1)
            val v = i + 2
            if (v + l > len) break
            when (opt) {
                53 -> msgType = Net.u8(f, v)
                1 -> mask = Net.u32(f, v)
                3 -> router = Net.u32(f, v)
                6 -> { var k = 0; while (k + 4 <= l) { dns.add(Net.u32(f, v + k)); k += 4 } }
                51 -> lease = Net.u32(f, v).toLong() and 0xffffffffL  // unsigned (0xffffffff = infinite)
                54 -> server = Net.u32(f, v)
            }
            i = v + l
        }
        val yiaddr = Net.u32(f, b + 16)
        val srcMac = f.copyOfRange(6, 12)
        if (mask == 0) mask = 0xFFFFFF00.toInt()  // no option 1: default to /24
        val ls = Lease(yiaddr, mask, router, dns, server, srcMac, lease)
        when (msgType) {
            2 -> { log("DHCP OFFER $ls"); offer = ls }
            5 -> { log("DHCP ACK   $ls"); ack = ls }
            6 -> { log("DHCP NAK"); offer = null; ack = null; nak = true }
            else -> return false
        }
        return true
    }

    private fun build(
        msgType: Int, ciaddr: Int, dstMac: ByteArray?, reqIp: Int, serverId: Int,
        broadcast: Boolean, dstIp: Int = -1
    ): ByteArray {
        val opts = ArrayList<Byte>()
        fun opt(code: Int, vararg v: Byte) { opts.add(code.toByte()); opts.add(v.size.toByte()); opts.addAll(v.toList()) }
        opt(53, msgType.toByte())
        opt(61, 1, *mac)
        if (reqIp != 0) opt(50, *Net.intToIp(reqIp))
        if (serverId != 0) opt(54, *Net.intToIp(serverId))
        opt(12, *hostname.toByteArray(Charsets.US_ASCII))
        opt(55, 1, 3, 6, 15, 51, 54)
        opts.add(0xFF.toByte())

        val bootpLen = 240 + opts.size
        val udpLen = 8 + bootpLen
        val ipLen = 20 + udpLen
        val f = ByteArray(14 + ipLen)

        Net.ethHeader(dstMac ?: Net.BROADCAST_MAC, mac, Net.ETHERTYPE_IP, f)
        // IPv4
        f[14] = 0x45; f[15] = 0
        Net.put16(f, 16, ipLen)
        Net.put16(f, 18, Random.nextInt(0xffff)); Net.put16(f, 20, 0)
        f[22] = 64; f[23] = 17
        Net.put32(f, 26, ciaddr)
        Net.put32(f, 30, if (broadcast) -1 else dstIp)
        Net.put16(f, 24, Net.checksum(f, 14, 20))
        // UDP (checksum is optional over IPv4 -> 0)
        Net.put16(f, 34, 68); Net.put16(f, 36, 67); Net.put16(f, 38, udpLen); Net.put16(f, 40, 0)
        // BOOTP
        val b = 42
        f[b] = 1; f[b + 1] = 1; f[b + 2] = 6; f[b + 3] = 0
        Net.put32(f, b + 4, xid)
        Net.put16(f, b + 8, 0)
        Net.put16(f, b + 10, if (broadcast) 0x8000 else 0)
        Net.put32(f, b + 12, ciaddr)
        System.arraycopy(mac, 0, f, b + 28, 6)
        Net.put32(f, b + 236, 0x63825363)
        for (k in opts.indices) f[b + 240 + k] = opts[k]
        return f
    }
}
