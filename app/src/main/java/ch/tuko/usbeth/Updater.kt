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
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the GitHub releases: reads the latest release, downloads its APK and SHA256SUMS,
 * and installs the APK only if its checksum matches, it is the same package with a higher
 * versionCode, and it is signed with the certificate of the installed app. The signature is the
 * real trust anchor: Android itself refuses an update signed with another key.
 *
 * Installing kills the process (and the bridge): [EthVpnService] calls [install] when the screen
 * is off, and [BootReceiver] restarts the bridge on MY_PACKAGE_REPLACED.
 */
object Updater {
    const val RELEASES_API = "https://api.github.com/repos/TuKo1982/UsbEth/releases/latest"
    private const val ACTION_INSTALL_STATUS = "ch.tuko.usbeth.INSTALL_STATUS"
    const val NOTIF_ID = 4
    private const val CHANNEL = "usbeth_update"

    /** Version downloaded and verified, ready to install (null: none). */
    @Volatile var ready: String? = null
    @Volatile var lastResult: String? = null
    /** Release notes of [ready] (GitHub release body). */
    @Volatile var notes = ""
    @Volatile private var justDownloaded = false
    @Volatile private var busy = false

    private fun apk(ctx: Context) = File(ctx.filesDir, "update.apk")

    /** Checks the latest release and downloads it if newer (background thread). */
    fun check(ctx: Context, api: String = RELEASES_API, done: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        Thread({
            try {
                // left over from an update already installed (the process restarted since)
                if (ready == null) apk(ctx).delete()
                lastResult = checkNow(ctx.applicationContext, api)
            } catch (e: Exception) {
                lastResult = "update check failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
            EthVpnService.log(lastResult!!)
            if (justDownloaded) { justDownloaded = false; offer(ctx.applicationContext) }
            done?.invoke()
        }, "usbeth-update").start()
    }

    private fun checkNow(ctx: Context, api: String): String {
        val rel = JSONObject(get(api).toString(Charsets.UTF_8))
        val tag = rel.getString("tag_name")
        val latest = tag.removePrefix("v")
        val current = BuildConfig.VERSION_NAME
        if (compare(latest, current) <= 0) { ready = null; return "up to date ($current, latest release $tag)" }
        if (ready == latest && apk(ctx).isFile) return "update $latest already downloaded"

        val assets = rel.getJSONArray("assets")
        var apkUrl: String? = null; var apkName: String? = null; var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (name.endsWith(".apk")) { apkName = name; apkUrl = a.getString("browser_download_url") }
            if (name == "SHA256SUMS") sumsUrl = a.getString("browser_download_url")
        }
        if (apkUrl == null || sumsUrl == null) return "release $tag: no APK or no SHA256SUMS, ignored"

        // "<hex>  <name>" (sha256sum format, optional '*' before the name)
        val sums = get(sumsUrl).toString(Charsets.UTF_8)
        val want = sums.lineSequence().map { it.trim().split(Regex("\\s+"), 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == apkName }?.get(0)?.lowercase()
            ?: return "release $tag: $apkName missing from SHA256SUMS, ignored"

        EthVpnService.log("update $latest available, downloading $apkName")
        val tmp = File(ctx.filesDir, "update.part")
        download(apkUrl, tmp)
        val got = sha256(tmp)
        if (got != want) { tmp.delete(); return "update $latest: SHA-256 mismatch, discarded" }
        verifyApk(ctx, tmp)?.let { tmp.delete(); return "update $latest: $it, discarded" }
        if (!tmp.renameTo(apk(ctx))) return "update $latest: cannot store the APK"
        ready = latest
        notes = rel.optString("body")
        justDownloaded = true
        return "update $latest downloaded and verified, installed when the screen is off"
    }

    /** null if the APK is acceptable, else the reason. */
    private fun verifyApk(ctx: Context, f: File): String? {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val new = pm.getPackageArchiveInfo(f.path, flags) ?: return "not a valid APK"
        val cur = pm.getPackageInfo(ctx.packageName, flags)
        if (new.packageName != ctx.packageName) return "other package (${new.packageName})"
        if (new.longVersionCode <= cur.longVersionCode) return "versionCode ${new.longVersionCode} not newer"
        if (certs(new) != certs(cur) || certs(cur).isEmpty()) return "signed with another certificate"
        return null
    }

    private fun certs(p: PackageInfo): Set<String> {
        val si = p.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    /**
     * Offers the install at once: the dialog, plus a notification in case Android does not let
     * the dialog open from the background (allowed here while the auto-OK service is enabled).
     */
    private fun offer(ctx: Context) {
        val open = Intent(ctx, UpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "USB Ethernet updates", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_eth)
            .setContentTitle("USB Ethernet $ready is available")
            .setContentText("Select to install it now, otherwise it is installed when the screen is off.")
            .setContentIntent(PendingIntent.getActivity(ctx, 4, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build())
        try { ctx.startActivity(open) } catch (e: Exception) { EthVpnService.log("update: cannot open the install dialog (${e.message})") }
    }

    /** Installs the downloaded update. The process is killed when the install succeeds. */
    fun install(ctx: Context): Boolean {
        val f = apk(ctx)
        val v = ready
        if (v == null || !f.isFile) return false
        EthVpnService.log("installing update $v")
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(f.length())
        // self-update without the confirmation dialog (UPDATE_PACKAGES_WITHOUT_USER_ACTION)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        return try {
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { out ->
                    FileInputStream(f).use { it.copyTo(out) }
                    s.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    ctx, id, Intent(ACTION_INSTALL_STATUS).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                s.commit(status.intentSender)
            }
            true
        } catch (e: Exception) {
            EthVpnService.log("update install failed: ${e.message}")
            false
        }
    }

    /** Install result (only reached on failure or confirmation: success kills the process). */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android wants a confirmation: show its dialog (works when the app is in front)
                    EthVpnService.log("update: Android asks for a confirmation")
                    @Suppress("DEPRECATION")
                    val confirm: Intent? = i.getParcelableExtra(Intent.EXTRA_INTENT)
                    try { confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) } }
                    catch (e: Exception) { EthVpnService.log("update: cannot show the confirmation (${e.message})") }
                }
                PackageInstaller.STATUS_SUCCESS -> EthVpnService.log("update installed")
                else -> {
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    EthVpnService.log("update install failed ($st): $msg")
                    ready = null
                    apk(ctx).delete()
                }
            }
        }
    }

    /** -1, 0, 1 comparing dotted versions ("1.10.0" > "1.9.2"). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UsbEth/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json, */*")
            if (responseCode != 200) throw java.io.IOException("HTTP $responseCode for $url")
        }

    private fun get(url: String): ByteArray = open(url).run { try { inputStream.readBytes() } finally { disconnect() } }

    private fun download(url: String, to: File) {
        val c = open(url)
        try { c.inputStream.use { inp -> to.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
