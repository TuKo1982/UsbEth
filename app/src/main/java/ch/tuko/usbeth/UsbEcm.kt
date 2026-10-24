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

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Minimal user-space CDC-ECM driver.
 *
 * Most USB Ethernet chips (Realtek RTL8152/8153/8156, ASIX AX88179A/B...) expose several USB
 * configurations: a vendor one (handled by r8152 / ax88179_178a in the kernel, missing here)
 * and a standard CDC-ECM one (control interface class 2/6 + data interface class 10).
 * We select the ECM configuration: raw Ethernet frames then flow over the bulk endpoints,
 * one frame per transfer (terminated by a short packet / ZLP).
 */
class UsbEcm(private val usb: UsbManager, val dev: UsbDevice, private val log: (String) -> Unit) {

    private lateinit var conn: UsbDeviceConnection
    private lateinit var ctrlIf: UsbInterface
    private lateinit var dataIf: UsbInterface
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    private var epNotif: UsbEndpoint? = null
    lateinit var mac: ByteArray
        private set

    private val zlp = ByteArray(0)

    fun open(): Boolean {
        conn = usb.openDevice(dev) ?: run { log("openDevice failed (permission?)"); return false }

        var cfg: UsbConfiguration? = null
        var ctrl: UsbInterface? = null
        var data: UsbInterface? = null
        for (i in 0 until dev.configurationCount) {
            val c = dev.getConfiguration(i)
            var fc: UsbInterface? = null
            var fd: UsbInterface? = null
            for (j in 0 until c.interfaceCount) {
                val itf = c.getInterface(j)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM && itf.interfaceSubclass == 6) fc = itf
                // alt setting 1 of the data interface carries the 2 bulk endpoints
                if (itf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && itf.endpointCount == 2) fd = itf
            }
            if (fc != null && fd != null) { cfg = c; ctrl = fc; data = fd; break }
        }
        if (cfg == null || ctrl == null || data == null) {
            log("no CDC-ECM configuration on this device"); conn.close(); return false
        }
        ctrlIf = ctrl; dataIf = data
        log("ECM config = ${cfg.id}, ctrl if=${ctrlIf.id}, data if=${dataIf.id} alt=${dataIf.alternateSetting}")

        if (!conn.setConfiguration(cfg)) { log("setConfiguration(${cfg.id}) failed"); conn.close(); return false }
        if (!conn.claimInterface(ctrlIf, true)) { log("claiming control interface failed"); conn.close(); return false }
        if (!conn.claimInterface(dataIf, true)) { log("claiming data interface failed"); conn.close(); return false }
        if (!conn.setInterface(dataIf)) { log("setInterface(alt ${dataIf.alternateSetting}) failed"); conn.close(); return false }

        for (k in 0 until dataIf.endpointCount) {
            val e = dataIf.getEndpoint(k)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
        }
        log("bulk IN=0x%02x OUT=0x%02x maxPacket=%d".format(epIn.address, epOut.address, epOut.maxPacketSize))
        for (k in 0 until ctrlIf.endpointCount) {
            val e = ctrlIf.getEndpoint(k)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN) epNotif = e
        }

        var filter = 0x000E // DIRECTED | BROADCAST | ALL_MULTICAST
        mac = readMacFromDescriptors(cfg.id) ?: run {
            log("MAC not found in descriptors, using a random MAC + promiscuous mode")
            filter = filter or 0x0001
            ByteArray(6).also { Random.nextBytes(it); it[0] = ((it[0].toInt() and 0xFE) or 0x02).toByte() }
        }
        log("MAC ${Net.macStr(mac)}")

        superSpeedCapable = readSuperSpeedCapable()
        log("USB speed: ${speed.label}" + if (superSpeedCapable) " (USB 3.0 capable adapter)" else "")

        // SetEthernetPacketFilter (CDC ECM 6.2.4)
        val r = conn.controlTransfer(0x21, 0x43, filter, ctrlIf.id, null, 0, 1000)
        if (r < 0) log("SetEthernetPacketFilter rejected ($r), continuing")
        return true
    }

    enum class Speed(val label: String) { FULL("USB 1.1"), HIGH("USB 2.0"), SUPER("USB 3.0") }

    /** Negotiated speed, derived from the bulk packet size: 1024 at SuperSpeed, 512 at High Speed. */
    val speed: Speed
        get() = when {
            epIn.maxPacketSize >= 1024 -> Speed.SUPER
            epIn.maxPacketSize >= 512 -> Speed.HIGH
            else -> Speed.FULL
        }

    /** True if the adapter supports USB 3.0, even if it currently runs at USB 2.0. */
    var superSpeedCapable = false
        private set

    /** Fallback: a USB 3.0 adapter plugged in (or negotiated) at USB 2.0, reduced throughput. */
    val usbFallback get() = superSpeedCapable && speed != Speed.SUPER

    /**
     * Reads the BOS descriptor (USB 3.2 9.6.2) and looks for the "SuperSpeed USB" capability
     * (type 0x03). A USB 3.0 device declares it whatever speed it was enumerated at.
     */
    private fun readSuperSpeedCapable(): Boolean {
        val hdr = ByteArray(5)
        if (conn.controlTransfer(0x80, 0x06, 0x0F shl 8, 0, hdr, hdr.size, 1000) < 5) return false
        val total = Net.u8(hdr, 2) or (Net.u8(hdr, 3) shl 8)
        if (total < 5) return false
        val bos = ByteArray(total)
        val n = conn.controlTransfer(0x80, 0x06, 0x0F shl 8, 0, bos, total, 1000)
        var i = Net.u8(bos, 0)
        while (i + 2 < n) {
            val len = Net.u8(bos, i)
            if (len < 3) break
            if (Net.u8(bos, i + 1) == 0x10 && Net.u8(bos, i + 2) == 0x03) return true
            i += len
        }
        return false
    }

    /**
     * Finds the "Ethernet Networking" functional descriptor (type 0x24, subtype 0x0F)
     * of the ECM configuration and reads its iMACAddress string.
     */
    private fun readMacFromDescriptors(cfgValue: Int): ByteArray? {
        val raw = conn.rawDescriptors ?: return null
        var i = 0
        var inCfg = false
        while (i + 1 < raw.size) {
            val len = Net.u8(raw, i)
            if (len < 2) break
            val type = Net.u8(raw, i + 1)
            when (type) {
                0x02 -> inCfg = (i + 5 < raw.size) && Net.u8(raw, i + 5) == cfgValue // CONFIGURATION
                0x24 -> if (inCfg && len >= 13 && Net.u8(raw, i + 2) == 0x0F) {
                    val idx = Net.u8(raw, i + 3)
                    return readStringDescriptor(idx)?.let { s ->
                        val hex = s.filter { it.isLetterOrDigit() }
                        if (hex.length != 12) null
                        else ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    }
                }
            }
            i += len
        }
        return null
    }

    private fun readStringDescriptor(idx: Int): String? {
        if (idx == 0) return null
        val buf = ByteArray(255)
        val n = conn.controlTransfer(0x80, 0x06, (0x03 shl 8) or idx, 0x0409, buf, buf.size, 1000)
        if (n < 4) return null
        return String(buf, 2, n - 2, Charsets.UTF_16LE)
    }

    /**
     * Waits for the CDC NETWORK_CONNECTION "connected" notification (CDC 1.2 6.3.1) on the
     * control interface's interrupt endpoint, then for the link to stay up [LINK_STABLE_MS]
     * without a "disconnected": right after setConfiguration the AX88179B reports a stale
     * "connected" followed by a short "disconnected" (~250 ms) during which frames are lost.
     * Returns true if the link is up and stable, false after [timeoutMs], null if the adapter
     * has no notification endpoint.
     */
    fun waitLink(timeoutMs: Long): Boolean? {
        val ep = epNotif ?: return null
        val buf = ByteArray(maxOf(16, ep.maxPacketSize))
        val end = SystemClock.uptimeMillis() + timeoutMs
        var upSince = 0L   // 0 = link not (yet) seen up
        var lastState = -1
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (upSince != 0L && now - upSince >= LINK_STABLE_MS) { log("Ethernet link up"); return true }
            val left = end - now
            if (left <= 0) return false
            // some adapters repeat the state on every poll, others only report changes
            val wait = if (upSince != 0L) upSince + LINK_STABLE_MS - now else minOf(left, 500L)
            val n = conn.bulkTransfer(ep, buf, buf.size, wait.toInt().coerceAtLeast(1))
            if (n < 0) { SystemClock.sleep(10); continue }
            if (n < 8 || Net.u8(buf, 0) != 0xA1) continue
            when (Net.u8(buf, 1)) {
                0x00 -> {   // NETWORK_CONNECTION, wValue = 1 when connected
                    val up = Net.u8(buf, 2) != 0
                    if (!up && lastState != 0) log("Ethernet link down (cable?)")
                    lastState = if (up) 1 else 0
                    upSince = if (!up) 0L else if (upSince == 0L) SystemClock.uptimeMillis() else upSince
                }
                0x2A -> if (n >= 16) {   // CONNECTION_SPEED_CHANGE: DLBitRate, ULBitRate, little endian
                    val down = Integer.reverseBytes(Net.u32(buf, 8)).toLong() and 0xffffffffL
                    if (down != lastSpeed) { lastSpeed = down; if (down > 0) log("link speed: ${down / 1_000_000} Mbit/s") }
                }
            }
        }
    }

    /** Receives one frame; returns its length, 0 if nothing / ZLP, <0 on error. */
    fun rx(buf: ByteArray, timeoutMs: Int = 500): Int {
        val t0 = SystemClock.uptimeMillis()
        val n = conn.bulkTransfer(epIn, buf, buf.size, timeoutMs)
        if (n >= 0) return n
        // bulkTransfer returns -1 both on timeout and on error: an early failure is an error
        return if (SystemClock.uptimeMillis() - t0 >= timeoutMs - 50) 0 else -1
    }

    fun tx(frame: ByteArray, len: Int): Boolean {
        val n = conn.bulkTransfer(epOut, frame, len, 1000)
        if (n != len) return false
        if (len % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, zlp, 0, 200)
        return true
    }

    // ------------------------------------------------------------------ asynchronous mode
    //
    // Once the bridge is up, several URBs stay queued in each direction: the adapter always has
    // a receive buffer ready, and sending no longer waits for the previous transfer to finish.
    // requestWait() returns IN and OUT completions mixed together: only one thread may call reap().

    private val rxReqs = ArrayList<UsbRequest>()
    private val txReqs = ArrayList<UsbRequest>()
    private val txFree = ArrayBlockingQueue<UsbRequest>(TX_DEPTH)
    private val rxFrame = ByteArray(RX_BUF)

    fun startAsync(): Boolean {
        repeat(RX_DEPTH) {
            val r = UsbRequest()
            if (!r.initialize(conn, epIn)) { log("UsbRequest IN: initialize failed"); return false }
            val b = ByteBuffer.allocateDirect(RX_BUF)
            r.clientData = b
            rxReqs.add(r)
            if (!r.queue(b)) { log("UsbRequest IN: queue failed"); return false }
        }
        repeat(TX_DEPTH) {
            val r = UsbRequest()
            if (!r.initialize(conn, epOut)) { log("UsbRequest OUT: initialize failed"); return false }
            r.clientData = ByteBuffer.allocateDirect(RX_BUF)
            txReqs.add(r)
            txFree.add(r)
        }
        log("async USB: $RX_DEPTH receive + $TX_DEPTH send requests queued")
        return true
    }

    /**
     * Waits for one completion. Received frame -> [onRx], then the request is queued again;
     * finished send -> the request goes back to the pool. Returns 1 if handled, 0 on timeout,
     * -1 on error.
     */
    fun reap(timeoutMs: Long, onRx: (ByteArray, Int) -> Unit): Int {
        val r = try { conn.requestWait(timeoutMs) } catch (_: TimeoutException) { return 0 } ?: return -1
        if (r.endpoint === epIn) {
            val b = r.clientData as ByteBuffer
            val n = b.position()
            if (n > 0) { b.flip(); b.get(rxFrame, 0, n); onRx(rxFrame, n) }
            b.clear()
            if (!r.queue(b)) return -1
        } else {
            txFree.offer(r)
        }
        return 1
    }

    /** [waitMs] = 0 from the reap() thread: it alone frees requests, so it must not wait for one. */
    fun txAsync(frame: ByteArray, len: Int, waitMs: Long = 1000): Boolean {
        val r = txFree.poll(waitMs, TimeUnit.MILLISECONDS) ?: return false
        val b = r.clientData as ByteBuffer
        b.clear()
        b.put(frame, 0, len)
        // like Linux usbnet: one padding byte instead of a ZLP when the frame is a multiple of the packet size
        if (len % epOut.maxPacketSize == 0) b.put(0)
        b.flip()
        if (!r.queue(b)) { txFree.offer(r); return false }
        return true
    }

    fun close() {
        for (r in rxReqs + txReqs) try { r.cancel() } catch (_: Exception) {}
        try { conn.releaseInterface(dataIf) } catch (_: Exception) {}
        try { conn.releaseInterface(ctrlIf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
        // the kernel killed the URBs when the file descriptor was closed: the requests can be freed
        for (r in rxReqs + txReqs) try { r.close() } catch (_: Exception) {}
        rxReqs.clear(); txReqs.clear(); txFree.clear()
    }

    private var lastSpeed = -1L

    companion object {
        private const val LINK_STABLE_MS = 400L
        private const val RX_DEPTH = 16
        private const val TX_DEPTH = 16
        private const val RX_BUF = 2048
    }
}
