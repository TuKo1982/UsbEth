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

/** Authorship shown on the app screen and as the first log line of every process start. */
object Credits {
    const val AUTHOR = "Renaud Schweingruber"
    const val LICENSE = "GPL-3.0-or-later"
    const val YEAR = "2026"

    val banner get() = "UsbEth ${BuildConfig.VERSION_NAME} by $AUTHOR - free software, $LICENSE"
    val footer get() = "UsbEth ${BuildConfig.VERSION_NAME}  ·  © $YEAR $AUTHOR  ·  $LICENSE"
}
