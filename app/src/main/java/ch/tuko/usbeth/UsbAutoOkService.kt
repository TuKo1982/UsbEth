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

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional accessibility service (enabled with adb, see README): presses OK in the system USB
 * dialog ("open USB Ethernet?" / "allow USB Ethernet to access?") so that the bridge comes back
 * without the remote after a wake-up or a reboot. Android TV's dialog has no "always" option and
 * the permission is lost every time the adapter is re-enumerated.
 *
 * Only acts on a SystemUI window that names both this app and a CDC-ECM adapter: OK if we have
 * no permission for that adapter yet, otherwise Back. The "open USB Ethernet?" dialog shows up
 * ~10 s after the adapter, once the service has already obtained access and reconnected, and
 * would stay on screen.
 *
 * Both dialogs can be stacked: "open" sits under "allow" and comes back when "allow" closes, with
 * no later event to act on. So every SystemUI event also starts a short watch that re-checks the
 * active window, instead of relying on an event arriving at the right moment.
 */
class UsbAutoOkService : AccessibilityService() {

    companion object {
        private const val SYSTEMUI = "com.android.systemui"
        private val POSITIVE_IDS = listOf("$SYSTEMUI:id/bottom_sheet_positive_button", "android:id/button1")
        private val POSITIVE_TEXTS = setOf("ok", "allow", "open", "autoriser", "ouvrir", "zulassen", "öffnen")
        /** Minimum time between two actions; also the retry period if a click had no effect. */
        private const val ACTION_GAP_MS = 2000L
        private const val WATCH_PERIOD_MS = 500L
        private const val WATCH_MS = 15000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var watchUntil = 0L
    private val watch = object : Runnable {
        override fun run() {
            check()
            if (SystemClock.uptimeMillis() < watchUntil) handler.postDelayed(this, WATCH_PERIOD_MS)
        }
    }

    override fun onServiceConnected() { EthVpnService.log("auto-OK service enabled") }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(watch)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(ev: AccessibilityEvent) {
        if (ev.packageName != SYSTEMUI) return
        val now = SystemClock.uptimeMillis()
        if (now >= watchUntil) {
            handler.removeCallbacks(watch)
            handler.postDelayed(watch, WATCH_PERIOD_MS)
        }
        watchUntil = now + WATCH_MS
        check()
    }

    private fun check() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < ACTION_GAP_MS) return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val adapters = UsbUtil.findAdapters(usb)
        if (adapters.isEmpty()) return
        val root = rootInActiveWindow ?: return
        if (root.packageName != SYSTEMUI) return
        if (root.findAccessibilityNodeInfosByText(getString(R.string.app_name)).isEmpty()) return
        val named = adapters.filter { d -> d.productName.isNullOrBlank() || root.findAccessibilityNodeInfosByText(d.productName!!).isNotEmpty() }
        if (named.isEmpty()) return

        lastAction = now
        if (named.all { usb.hasPermission(it) }) {
            EthVpnService.log("auto-OK: USB dialog for an adapter already allowed, dismissed")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val ok = positiveButton(root)
        if (ok == null) { EthVpnService.log("auto-OK: USB dialog without a known OK button: ${describe(root)}"); return }
        EthVpnService.log("auto-OK: pressing \"${ok.text ?: ok.viewIdResourceName}\" in the USB dialog")
        ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun positiveButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in POSITIVE_IDS) root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled }?.let { return it }
        return nodes(root).firstOrNull {
            it.isClickable && it.isEnabled && it.text?.toString()?.trim()?.lowercase() in POSITIVE_TEXTS
        }
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val todo = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (todo.isNotEmpty() && out.size < 200) {
            val n = todo.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { todo.add(it) }
        }
        return out
    }

    /** Dialog content for the log, to adapt [POSITIVE_IDS] to another TV. */
    private fun describe(root: AccessibilityNodeInfo) = nodes(root)
        .filter { it.text != null || it.viewIdResourceName != null }
        .take(30)
        .joinToString(" | ") { "${it.viewIdResourceName ?: "-"}=${it.text ?: ""}${if (it.isClickable) "*" else ""}" }
}
