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

import java.util.concurrent.ConcurrentHashMap

/** Minimal ARP client/responder (IPv4 over Ethernet). */
class Arp(private val mac: ByteArray) {
    @Volatile var myIp: Int = 0
    private val cache = ConcurrentHashMap<Int, ByteArray>()

    fun lookup(ip: Int): ByteArray? = cache[ip]

    fun request(target: Int): ByteArray {
        val f = ByteArray(42)
        Net.ethHeader(Net.BROADCAST_MAC, mac, Net.ETHERTYPE_ARP, f)
        fill(f, 1, mac, myIp, ByteArray(6), target)
        return f
    }

    /**
     * Handles a received ARP frame. Returns a reply frame to send, or null.
     */
    fun onFrame(f: ByteArray, len: Int): ByteArray? {
        if (len < 42) return null
        val op = Net.u16(f, 20)
        val senderMac = f.copyOfRange(22, 28)
        val senderIp = Net.u32(f, 28)
        val targetIp = Net.u32(f, 38)
        if (senderIp != 0) cache[senderIp] = senderMac
        if (op == 1 && myIp != 0 && targetIp == myIp) {
            val r = ByteArray(42)
            Net.ethHeader(senderMac, mac, Net.ETHERTYPE_ARP, r)
            fill(r, 2, mac, myIp, senderMac, senderIp)
            return r
        }
        return null
    }

    private fun fill(f: ByteArray, op: Int, sha: ByteArray, spa: Int, tha: ByteArray, tpa: Int) {
        Net.put16(f, 14, 1)         // HTYPE Ethernet
        Net.put16(f, 16, 0x0800)    // PTYPE IPv4
        f[18] = 6; f[19] = 4
        Net.put16(f, 20, op)
        System.arraycopy(sha, 0, f, 22, 6)
        Net.put32(f, 28, spa)
        System.arraycopy(tha, 0, f, 32, 6)
        Net.put32(f, 38, tpa)
    }
}
