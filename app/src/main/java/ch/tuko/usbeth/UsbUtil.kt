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

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

object UsbUtil {
    /** True if the device exposes a CDC-ECM configuration. */
    fun hasEcm(d: UsbDevice): Boolean {
        for (i in 0 until d.configurationCount) {
            val c = d.getConfiguration(i)
            var ctrl = false; var data = false
            for (j in 0 until c.interfaceCount) {
                val itf = c.getInterface(j)
                if (itf.interfaceClass == UsbConstants.USB_CLASS_COMM && itf.interfaceSubclass == 6) ctrl = true
                if (itf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) data = true
            }
            if (ctrl && data) return true
        }
        return false
    }

    fun findAdapter(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { hasEcm(it) }

    fun findAdapters(usb: UsbManager): List<UsbDevice> = usb.deviceList.values.filter { hasEcm(it) }
}
