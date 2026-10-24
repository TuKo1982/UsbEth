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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bridge between a USB Ethernet adapter (user-space CDC-ECM) and the TUN interface provided
 * by VpnService.
 *
 *  TUN -> ETH: bare IPv4 packet, we add the Ethernet header (ARP / gateway) and send it.
 *  ETH -> TUN: ARP handled locally, DHCP consumed, IPv4 addressed to us written to the TUN.
 */
class EthVpnService : VpnService() {

    companion object {
        const val ACTION_START = "ch.tuko.usbeth.START"
        const val ACTION_STOP = "ch.tuko.usbeth.STOP"
        private const val ACTION_USB_PERM = "ch.tuko.usbeth.USB_PERMISSION_SVC"
        private const val TAG = "UsbEth"
        private const val CHANNEL = "usbeth"
        private const val NOTIF_ID = 1
        private const val RX_FRAMES = 256

        @Volatile var running = false
        @Volatile var statusText = "Stopped"
        private val lines = ArrayDeque<String>()
        @Volatile var listener: (() -> Unit)? = null
        /** Advice to show the user (USB 2.0 fallback...), null when all is well. */
        @Volatile var warning: String? = null
        private const val ALERT_CHANNEL = "usbeth_alert"
        private const val ALERT_ID = 2
        private const val RETRY_ID = 3
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun log(s: String) {
            Log.i(TAG, s)
            synchronized(lines) {
                lines.addLast("${fmt.format(Date())} $s")
                while (lines.size > 60) lines.removeFirst()
            }
            listener?.invoke()
        }
        fun logText(): String = synchronized(lines) { lines.joinToString("\n") }
        private fun setStatus(s: String) { statusText = s; listener?.invoke() }

        // first line of every process start: app opened, boot, or adapter plugged in
        init { log(Credits.banner) }
    }

    private var ecm: UsbEcm? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    @Volatile private var stopping = false
    private val threads = ArrayList<Thread>()

    private lateinit var arp: Arp
    private lateinit var dhcp: Dhcp
    @Volatile private var lease: Dhcp.Lease? = null
    private val txLock = Any()

    /** Received frame in transit from the USB thread to the TUN thread. */
    private class Frame(val buf: ByteArray, var len: Int = 0)
    private val rxFree = ArrayBlockingQueue<Frame>(RX_FRAMES).apply { repeat(RX_FRAMES) { add(Frame(ByteArray(2048))) } }
    private val rxFull = ArrayBlockingQueue<Frame>(RX_FRAMES)
    @Volatile private var rxDropped = 0L
    @Volatile private var async = false
    @Volatile private var rxThread: Thread? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (d != null && ecm != null && d.deviceName == ecm!!.dev.deviceName) {
                log("adapter unplugged")
                stopBridge()
            }
        }
    }

    /** Answer to the USB permission request made by the service itself (start at boot). */
    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                log("USB permission granted")
                getSystemService(NotificationManager::class.java).cancel(RETRY_ID)
                onPermitted(d)
            } else if (d != null && permAttempts < 2) {
                // a remote key press often lands on the dialog (focus is on "Cancel"): ask again
                log("USB permission denied, asking again in 15 s")
                askPermission(d, 15_000)
            } else if (candidates.isNotEmpty() || fallbackDev != null) {
                log("USB permission denied, trying the next adapter")
                nextCandidate()
            } else {
                log("USB permission denied")
                stopBridge("Failed: USB adapter not authorized")
                notifyRetry()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        ContextCompat.registerReceiver(this, permReceiver, IntentFilter(ACTION_USB_PERM), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { log("stop requested"); stopBridge(); return START_NOT_STICKY }
            ACTION_START -> {
                // startForegroundService() requires startForeground() on every call, even if we stop right away
                updateNotification(if (running) statusText else "Connecting...")
                val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (dev == null) { log("no device in the intent"); stopBridge(); return START_NOT_STICKY }
                if (running) { log("already running"); return START_STICKY }
                if (selecting) { log("adapter selection already in progress"); return START_NOT_STICKY }
                val usb = getSystemService(Context.USB_SERVICE) as UsbManager
                // the requested adapter first, then the other CDC-ECM adapters plugged in
                candidates.clear()
                candidates.add(dev)
                UsbUtil.findAdapters(usb).filter { it.deviceName != dev.deviceName }.forEach { candidates.add(it) }
                multi = candidates.size > 1
                fallbackDev = null; fallbackSilent = false
                selecting = true
                if (multi) log("${candidates.size} adapters plugged in: looking for the one with an Ethernet link")
                nextCandidate()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun begin(dev: UsbDevice) {
        selecting = false
        if (running) return
        running = true; stopping = false; shutDown.set(false)
        Thread({ worker(dev) }, "usbeth-main").also { threads.add(it) }.start()
    }

    private var permAttempts = 0

    // ------------------------------------------------------------------ adapter selection

    private val candidates = ArrayDeque<UsbDevice>()
    private var multi = false
    @Volatile private var selecting = false
    /** Used if no adapter reports a link: one that does not report link state, else the first one. */
    private var fallbackDev: UsbDevice? = null
    private var fallbackSilent = false

    /** Tries the next candidate (main thread): permission first if needed, then the link test. */
    private fun nextCandidate() {
        val d = candidates.removeFirstOrNull()
        if (d == null) {
            val f = fallbackDev
            if (f != null) { log("no Ethernet link detected, trying ${adapterName(f)}"); begin(f) }
            else { selecting = false; stopBridge("Failed: no usable adapter"); notifyRetry() }
            return
        }
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.hasPermission(d)) onPermitted(d)
        else {
            // start at boot: on Android TV the USB dialog has no "always" checkbox, no permission
            // survives a reboot, so it must be asked for every time
            log("no USB permission for ${adapterName(d)}, asking the user")
            permAttempts = 0
            askPermission(d, 3_000)
        }
    }

    private fun onPermitted(d: UsbDevice) {
        if (!multi) { begin(d); return }
        Thread({
            val up = probeLink(d)
            Handler(Looper.getMainLooper()).post {
                if (stopping) return@post
                when (up) {
                    true -> {
                        log("Ethernet link on ${adapterName(d)} (${d.deviceName}): adapter selected")
                        candidates.clear(); begin(d)
                    }
                    null -> {
                        log("${adapterName(d)} does not report its link state, kept as a fallback")
                        if (fallbackDev == null || !fallbackSilent) { fallbackDev = d; fallbackSilent = true }
                        nextCandidate()
                    }
                    false -> {
                        log("no Ethernet link on ${adapterName(d)} (${d.deviceName})")
                        if (fallbackDev == null) fallbackDev = d
                        nextCandidate()
                    }
                }
            }
        }, "usbeth-probe").start()
    }

    /** Opens the adapter just long enough to read its link state. true/false, null if not reported. */
    private fun probeLink(d: UsbDevice): Boolean? {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val e = UsbEcm(usb, d) {}
        return try {
            if (!e.open()) false else e.waitLink(4_000)
        } catch (ex: Exception) {
            false
        } finally {
            try { e.close() } catch (_: Exception) {}
        }
    }

    /**
     * Opens the system USB permission dialog after [delayMs], preceded by a toast: the delay keeps
     * a remote key press in progress (navigating right after boot) from dismissing it at once.
     */
    private fun askPermission(dev: UsbDevice, delayMs: Long) {
        permAttempts++
        setStatus("USB adapter access requested: choose OK")
        updateNotification("Allow the USB adapter: choose OK")
        toast("USB Ethernet: a dialog will ask for access to the adapter.\nPress Up, then OK to allow it.")
        Handler(Looper.getMainLooper()).postDelayed({
            if (running || stopping) return@postDelayed
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val pi = PendingIntent.getBroadcast(
                this, 1, Intent(ACTION_USB_PERM).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usb.requestPermission(dev, pi)
        }, delayMs)
    }

    /** Last resort after a refusal: a notification that opens the app and reconnects. */
    private fun notifyRetry() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "USB Ethernet alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOCONNECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(RETRY_ID, Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_eth)
            .setContentTitle("USB Ethernet not connected")
            .setContentText("The USB adapter was not authorized. Tap to allow it and connect.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ worker

    private fun worker(dev: UsbDevice) {
        try {
            setStatus("Opening USB")
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val e = UsbEcm(usb, dev, ::log)
            if (!e.open()) { fail("could not open the ECM interface"); return }
            checkUsbSpeed(e)
            ecm = e
            arp = Arp(e.mac)
            dhcp = Dhcp(e.mac, dhcpHostname(), ::log)

            // otherwise the first DISCOVERs go out before link negotiation is over (~3-6 s)
            setStatus("Waiting for the Ethernet link...")
            when (e.waitLink(10_000)) {
                true -> {}
                false -> log("no link notification within 10 s, trying DHCP anyway")
                null -> log("no notification endpoint, DHCP without waiting for the link")
            }

            setStatus("DHCP...")
            val l = doDhcp() ?: run { fail("no DHCP lease (cable plugged in? link up?)"); return }
            lease = l
            arp.myIp = l.ip

            setStatus("Resolving gateway...")
            if (l.router != 0) {
                val gw = resolve(l.router) ?: run { fail("gateway ${Net.ipStr(l.router)} does not answer ARP"); return }
                log("gateway ${Net.ipStr(l.router)} = ${Net.macStr(gw)}")
            }

            setStatus("Creating the TUN interface")
            val b = Builder()
                .setSession("USB Ethernet")
                .setMtu(1500)
                .addAddress(Net.ipStr(l.ip), l.prefix)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)          // IPv6 captured then dropped: no leak through Wi-Fi
                .setBlocking(false)         // TUN read through poll() so that we can stop cleanly
            l.dns.forEach { b.addDnsServer(Net.ipStr(it)) }
            if (l.dns.isEmpty() && l.router != 0) b.addDnsServer(Net.ipStr(l.router))
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            val t = b.establish() ?: run { fail("establish() returned null (VPN consent?)"); return }
            tun = t
            tunOut = FileOutputStream(t.fileDescriptor)

            if (!e.startAsync()) { fail("could not queue USB requests"); return }
            async = true

            setStatus("Connected  ${Net.ipStr(l.ip)}/${l.prefix}  via ${adapterName(dev)} (${e.speed.label})")
            updateNotification("Connected ${Net.ipStr(l.ip)}")
            log("bridge up")

            threads.add(Thread({ guarded { tunWriteLoop() } }, "usbeth-tun").also { it.start() })
            threads.add(Thread({ guarded { rxLoop() } }, "usbeth-rx").also { rxThread = it; it.start() })
            threads.add(Thread({ guarded { txLoop(t) } }, "usbeth-tx").also { it.start() })
            renewLoop()
        } catch (ex: Exception) {
            if (!stopping) { log("error: $ex"); fail(ex.message ?: "error") }
        }
    }

    /** An exception in a loop stops the bridge instead of killing the process. */
    private fun guarded(body: () -> Unit) {
        try { body() } catch (ex: Exception) {
            if (!stopping) { log("error in ${Thread.currentThread().name}: $ex"); fail(ex.message ?: "error") }
        }
    }

    private fun adapterName(d: UsbDevice) =
        listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB adapter" }

    /** DHCP host name (option 12) derived from the device model, e.g. "OLED950-12". */
    private fun dhcpHostname() =
        Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').take(32).ifEmpty { "android-tv" }

    /**
     * Warns the user when a USB 3.0 adapter runs at USB 2.0 (USB 3.0 contacts not fully engaged,
     * USB 2.0 port or hub, hub cable): toast shown over any app, notification, and advice on the
     * app screen. Everything is cleared the next time the adapter comes up at USB 3.0.
     */
    private fun checkUsbSpeed(e: UsbEcm) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!e.usbFallback) {
            if (warning != null) log("USB 3.0 restored")
            warning = null
            nm.cancel(ALERT_ID)
            listener?.invoke()
            return
        }
        val name = adapterName(e.dev)
        val msg = "$name runs at USB 2.0 although it supports USB 3.0: reduced throughput.\n" +
            "Unplug it, then plug it back in firmly and straight, into the USB 3.0 port of the TV " +
            "(or of the hub). If it happens again, try another port: the adapter, the hub or its " +
            "cable may be at fault."
        log("WARNING: USB 2.0 fallback detected")
        warning = msg
        listener?.invoke()
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "USB Ethernet alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ALERT_ID, Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_eth)
            .setContentTitle("Adapter running at USB 2.0")
            .setContentText("Plug $name back in firmly to get USB 3.0")
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
        toast("USB Ethernet: adapter running at USB 2.0, plug it back in firmly")
    }

    private fun fail(msg: String) {
        log("failed: $msg")
        stopBridge("Failed: $msg")
    }

    // ------------------------------------------------------------------ dispatch

    /** Handles a frame received from the adapter. */
    private fun handleFrame(f: ByteArray, n: Int) {
        if (n < Net.ETH_HDR) return
        when (Net.u16(f, 12)) {
            Net.ETHERTYPE_ARP -> arp.onFrame(f, n)?.let { send(it, it.size) }
            Net.ETHERTYPE_IP -> {
                if (dhcp.onFrame(f, n)) return
                val out = tunOut ?: return
                val l = lease ?: return
                if (n < Net.ETH_HDR + 20) return
                // only what is addressed to us (in promiscuous mode we see the whole LAN)
                val dst = Net.u32(f, Net.ETH_HDR + 16)
                if (dst != l.ip && dst != -1 && dst != (l.ip or l.mask.inv()) && !Net.isMulticast(dst)) return
                // actual IP length: strips the Ethernet padding of short frames
                val ipLen = minOf(Net.u16(f, Net.ETH_HDR + 2), n - Net.ETH_HDR)
                try { out.write(f, Net.ETH_HDR, ipLen) } catch (_: Exception) {}
            }
            else -> {}
        }
    }

    private fun send(f: ByteArray, len: Int): Boolean {
        val e = ecm ?: return false
        if (async) return e.txAsync(f, len, if (Thread.currentThread() === rxThread) 0 else 1000)
        synchronized(txLock) { return e.tx(f, len) }
    }

    /** Pumps received frames for [ms] milliseconds or until [done] is true. */
    private fun pump(ms: Long, done: () -> Boolean) {
        val e = ecm ?: return
        val buf = ByteArray(2048)
        val deadline = System.currentTimeMillis() + ms
        while (!stopping && !done() && System.currentTimeMillis() < deadline) {
            val n = e.rx(buf, 200)
            if (n > 0) handleFrame(buf, n)
            else if (n < 0) Thread.sleep(10)
        }
    }

    private fun doDhcp(): Dhcp.Lease? {
        for (attempt in 1..8) {
            if (stopping) return null
            dhcp.newTransaction()
            log("DHCP DISCOVER (attempt $attempt)")
            val d = dhcp.discover()
            send(d, d.size)
            // 1 s for the first attempts (frame lost at startup), then 3 s
            pump(if (attempt <= 3) 1000 else 3000) { dhcp.offer != null }
            val o = dhcp.offer ?: continue
            val req = dhcp.request(o)
            send(req, req.size)
            pump(3000) { dhcp.ack != null || dhcp.nak }
            dhcp.ack?.let { return it }
        }
        return null
    }

    private fun resolve(ip: Int): ByteArray? {
        for (attempt in 1..8) {
            if (stopping) return null
            val r = arp.request(ip)
            send(r, r.size)
            pump(1000) { arp.lookup(ip) != null }
            arp.lookup(ip)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ loops

    /** The only thread that reaps USB completions (both receive and send). */
    private fun rxLoop() {
        val e = ecm ?: return
        var errors = 0
        while (!stopping) {
            val n = try { e.reap(500, ::enqueueRx) } catch (ex: Exception) { -1 }
            if (n >= 0) errors = 0
            else {
                if (++errors >= 50) {
                    if (!stopping) { log("repeated USB receive errors"); stopBridge() }
                    return
                }
                Thread.sleep(10)
            }
        }
    }

    private fun txLoop(t: ParcelFileDescriptor) {
        val fd = t.fileDescriptor
        val e = ecm ?: return
        val pkt = ByteArray(2048)
        val frame = ByteArray(2048 + Net.ETH_HDR)
        val l = lease ?: return
        val subnetBcast = l.ip or l.mask.inv()
        val lastArp = HashMap<Int, Long>()
        val pfd = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        while (!stopping) {
            val n = try {
                Os.read(fd, pkt, 0, pkt.size)
            } catch (ex: ErrnoException) {
                if (ex.errno == OsConstants.EAGAIN) {
                    // nothing to read: wait with a timeout so that [stopping] is checked again
                    try { Os.poll(pfd, 500) } catch (_: ErrnoException) {}
                    continue
                }
                -1
            } catch (ex: Exception) { -1 }
            if (n < 0) { if (!stopping) { log("TUN closed"); stopBridge() }; return }
            if (n < 20 || (Net.u8(pkt, 0) ushr 4) != 4) continue   // IPv4 only
            val dst = Net.u32(pkt, 16)
            val dmac: ByteArray = when {
                dst == -1 || dst == subnetBcast -> Net.BROADCAST_MAC
                Net.isMulticast(dst) -> Net.multicastMac(dst)
                (dst and l.mask) == (l.ip and l.mask) || l.router == 0 -> {
                    val m = arp.lookup(dst)
                    if (m == null) {
                        val now = System.currentTimeMillis()
                        if (now - (lastArp[dst] ?: 0L) > 200) {
                            val r = arp.request(dst); send(r, r.size); lastArp[dst] = now
                            if (lastArp.size > 256) lastArp.clear()
                        }
                        continue
                    }
                    m
                }
                // the ARP cache follows the router's announcements (MAC change, failover...)
                else -> arp.lookup(l.router) ?: continue
            }
            Net.ethHeader(dmac, e.mac, Net.ETHERTYPE_IP, frame)
            System.arraycopy(pkt, 0, frame, Net.ETH_HDR, n)
            if (!send(frame, n + Net.ETH_HDR) && !stopping) log("USB send failed")
        }
    }

    private fun renewLoop() {
        var ok = true
        while (!stopping) {
            val l = lease ?: return
            val wait = if (ok) (l.leaseSec / 2).coerceIn(60L, 86400L) * 1000L else 60_000L
            val end = System.currentTimeMillis() + wait
            while (!stopping && System.currentTimeMillis() < end) Thread.sleep(1000)
            if (stopping) return
            ok = renew(l)
        }
    }

    /** Unicast renewal; on NAK, a full new negotiation. */
    private fun renew(l: Dhcp.Lease): Boolean {
        dhcp.newTransaction()
        for (i in 1..3) {
            log("DHCP renewal (attempt $i)")
            val r = dhcp.renew(l)
            send(r, r.size)
            pumpWait(2000) { dhcp.ack != null || dhcp.nak }
            if (dhcp.nak) return rebind(l)
            val a = dhcp.ack ?: continue
            return accept(l, a)
        }
        log("no answer to the renewal, retrying in 60 s")
        return false
    }

    private fun rebind(old: Dhcp.Lease): Boolean {
        log("lease refused (NAK), new DHCP request")
        for (attempt in 1..3) {
            if (stopping) return false
            dhcp.newTransaction()
            val d = dhcp.discover()
            send(d, d.size)
            pumpWait(3000) { dhcp.offer != null }
            val o = dhcp.offer ?: continue
            val req = dhcp.request(o)
            send(req, req.size)
            pumpWait(3000) { dhcp.ack != null || dhcp.nak }
            val a = dhcp.ack ?: continue
            return accept(old, a)
        }
        log("no answer to the new DHCP request, retrying in 60 s")
        return false
    }

    private fun accept(old: Dhcp.Lease, a: Dhcp.Lease): Boolean {
        if (a.ip != old.ip) {
            // the TUN address cannot change on the fly
            fail("the DHCP server assigned another IP (${Net.ipStr(a.ip)}), reconnect")
            return true
        }
        lease = a
        log("lease renewed")
        return true
    }

    /**
     * Called by the USB thread: copies the frame and hands it to the TUN thread, so that USB
     * requests are queued again without waiting for the TUN write.
     * Queue full: the frame is dropped, as on an overrun network card.
     */
    private fun enqueueRx(f: ByteArray, n: Int) {
        val fr = rxFree.poll() ?: run { rxDropped++; return }
        System.arraycopy(f, 0, fr.buf, 0, n)
        fr.len = n
        rxFull.offer(fr)
    }

    /** Parses received frames (ARP, DHCP, IPv4) and writes IPv4 to the TUN. */
    private fun tunWriteLoop() {
        var reported = 0L
        while (!stopping) {
            val fr = rxFull.poll(500, TimeUnit.MILLISECONDS) ?: continue
            try { handleFrame(fr.buf, fr.len) } finally { rxFree.offer(fr) }
            if (rxDropped - reported >= 1000) { reported = rxDropped; log("$rxDropped frames dropped (TUN queue full)") }
        }
    }

    /** Passive wait: reception is handled by rxLoop. */
    private fun pumpWait(ms: Long, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!stopping && !done() && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Full stop. stopSelf() alone is not enough: as long as the TUN is open the system stays
     * bound to the VpnService, which is then never destroyed (onDestroy() never called).
     * So everything is closed here, then we leave the foreground and stop.
     */
    private fun stopBridge(status: String = "Stopped") {
        shutdown(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val shutDown = AtomicBoolean(false)

    private fun shutdown(status: String = "Stopped") {
        // called from several threads (button, unplug, USB error...): only once
        if (!shutDown.compareAndSet(false, true)) return
        selecting = false; candidates.clear()
        stopping = true
        running = false
        // the loops see [stopping] within 1 s (USB / poll / sleep timeouts): wait for them before
        // closing, so that the USB connection is not closed under a running bulkTransfer
        for (th in threads) if (th !== Thread.currentThread()) {
            try { th.join(1500) } catch (_: InterruptedException) {}
        }
        threads.clear()
        async = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null; tunOut = null
        try { ecm?.close() } catch (_: Exception) {}
        ecm = null
        setStatus(status)
    }

    override fun onRevoke() { log("VPN revoked by the system"); stopBridge() }

    override fun onDestroy() {
        shutdown()
        try { unregisterReceiver(detachReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(permReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notification

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB Ethernet", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eth)
            .setContentTitle("USB Ethernet")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_ID, n)
    }
}
