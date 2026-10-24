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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.VpnService

/**
 * At boot: if an adapter is already plugged in and VPN consent was given before, restart the
 * bridge. USB permission is not required here: without a "use by default" association Android
 * does not grant it at boot, so the service asks for it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = UsbUtil.findAdapter(usb)
        if (dev == null) { EthVpnService.log("boot: no CDC-ECM adapter plugged in"); return }
        if (VpnService.prepare(ctx) != null) { EthVpnService.log("boot: no VPN consent yet, open the app"); return }
        EthVpnService.log("boot: ${dev.productName}, USB permission ${if (usb.hasPermission(dev)) "granted" else "to be requested"}")
        val i = Intent(ctx, EthVpnService::class.java)
            .setAction(EthVpnService.ACTION_START)
            .putExtra(UsbManager.EXTRA_DEVICE, dev)
        ctx.startForegroundService(i)
    }
}
