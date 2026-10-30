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

import android.app.AlertDialog
import android.app.NotificationManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Offered as soon as an update is downloaded and verified: install now, or later (then it is
 * installed the next time the screen turns off).
 */
class UpdateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(Updater.NOTIF_ID)
        val v = Updater.ready
        if (v == null) { finish(); return }
        val notes = plain(Updater.notes).let { if (it.length > 900) it.take(900) + "..." else it }
        AlertDialog.Builder(this)
            .setTitle("USB Ethernet $v is available")
            .setMessage(
                "Installed: ${BuildConfig.VERSION_NAME}\n\n" +
                (if (notes.isNotEmpty()) "$notes\n\n" else "") +
                "Installing restarts the app: the network drops for a few seconds, then comes back by itself."
            )
            .setPositiveButton("Install now") { _, _ ->
                if (!Updater.install(this)) EthVpnService.log("update: nothing to install")
                finish()
            }
            .setNegativeButton("Later") { _, _ ->
                EthVpnService.log("update $v postponed: installed when the screen is off")
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    /** Release notes (Markdown, wrapped at 100 columns) as flowing text: one paragraph per item. */
    private fun plain(md: String) = md.replace("\r", "").replace(Regex("\n[ \t]+"), " ").replace("`", "").trim()
}
