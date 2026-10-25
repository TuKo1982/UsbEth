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

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERM = "ch.tuko.usbeth.USB_PERMISSION"
        private const val REQ_VPN = 1
        private const val REQ_NOTIF = 2
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var warnView: TextView
    private lateinit var btn: Button
    private var pendingDev: UsbDevice? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_USB_PERM) return
            val dev: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) proceed(dev)
            else EthVpnService.log("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        warnView = findViewById(R.id.warn)
        btn = findViewById(R.id.btn)
        findViewById<TextView>(R.id.credits).text = Credits.footer
        btn.setOnClickListener { if (EthVpnService.running || EthVpnService.waiting) stopBridge() else connect() }
        btn.requestFocus()

        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(ACTION_USB_PERM), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        EthVpnService.listener = { runOnUiThread { refresh() } }
        refresh()
        // alerts (USB 2.0 fallback...): without this permission Android 13+ hides the notification
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        // am start ... --ez autoconnect true: start without the remote (adb deployment, retry notification)
        if (i?.getBooleanExtra(EXTRA_AUTOCONNECT, false) == true && !EthVpnService.running) { connect(); return }
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val dev: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            // reconnection after a wake-up (the system dialog comes late, often once the service
            // has already reconnected): no need to show the app over what the user is doing
            val background = EthVpnService.running || EthVpnService.waiting
            if (dev != null && !EthVpnService.running) {
                EthVpnService.log("adapter plugged in: ${dev.productName}")
                proceed(dev)
            }
            if (background) moveTaskToBack(true)
        }
    }

    private fun connect() {
        val dev = UsbUtil.findAdapter(usb)
        if (dev == null) { EthVpnService.log("no USB Ethernet (CDC-ECM) adapter found"); return }
        EthVpnService.log("adapter: ${dev.manufacturerName} ${dev.productName}")
        if (usb.hasPermission(dev)) { proceed(dev); return }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERM).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usb.requestPermission(dev, pi)
    }

    private fun proceed(dev: UsbDevice) {
        pendingDev = dev
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, REQ_VPN) else startBridge(dev)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) pendingDev?.let { startBridge(it) }
            else EthVpnService.log("VPN consent denied")
        }
    }

    private fun startBridge(dev: UsbDevice) {
        val i = Intent(this, EthVpnService::class.java)
            .setAction(EthVpnService.ACTION_START)
            .putExtra(UsbManager.EXTRA_DEVICE, dev)
        startForegroundService(i)
    }

    private fun stopBridge() {
        startService(Intent(this, EthVpnService::class.java).setAction(EthVpnService.ACTION_STOP))
    }

    private fun refresh() {
        status.text = EthVpnService.statusText
        logView.text = EthVpnService.logText()
        val w = EthVpnService.warning
        warnView.text = w?.let { "⚠ $it" }
        warnView.visibility = if (w != null) View.VISIBLE else View.GONE
        btn.text = if (EthVpnService.running || EthVpnService.waiting) "Disconnect" else "Connect"
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() {
        EthVpnService.listener = null
        try { unregisterReceiver(permReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
