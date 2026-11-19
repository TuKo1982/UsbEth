// UsbEth - Copyright (C) 2026 Renaud Schweingruber - SPDX-License-Identifier: GPL-3.0-or-later
package ch.tuko.usbeth;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LocalServerSocket;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Base network for the VPN. Runs as the adb shell user (app_process, started by {@link AdbLocal}),
 * NOT inside the app.
 *
 * Android treats a VPN with no underlying network as metered and congested, whatever the VPN app
 * declares: Play Store downloads and other background jobs then wait forever. With the Ethernet
 * adapter driven in userspace there is no other network, so this process creates one: a virtual
 * Ethernet card (TAP test interface, adopted by the Ethernet service once test interfaces are
 * included), which Android flags unmetered and not congested. It carries no traffic: this process
 * answers the DHCP and ARP of its fake gateway 198.18.0.1 so that it stays provisioned, and the
 * VPN declares it as its underlying network. All traffic still goes through the USB adapter.
 *
 * The shell user holds MANAGE_TEST_NETWORKS and NETWORK_SETTINGS, an app cannot. The classes of
 * the connectivity module are renamed (jarjar), hence the reflection.
 */
public final class BaseNet {
    static final String LOCK = "ch.tuko.usbeth.basenet";
    static final byte[] GW = {(byte) 198, 18, 0, 1}, CLIENT = {(byte) 198, 18, 0, 2};
    static final byte[] GW_MAC = {0x02, 0x55, 0x45, 0x54, 0x48, 0x01};

    // the interface lives as long as its descriptor: kept reachable for the whole process
    static Object tapIface, testNetMgr, ethMgr;
    static ParcelFileDescriptor tapFd;
    static ConnectivityManager cm;
    static LocalServerSocket lock;

    static void log(String s) {
        System.out.println(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + s);
    }

    public static void main(String[] args) {
        try {
            run(args.length > 0 ? args[0] : null);
        } catch (Throwable t) {
            log("error: " + t);
            t.printStackTrace(System.out);
        }
        System.exit(1);
    }

    static void run(String apk) throws Exception {
        try {
            lock = new LocalServerSocket(LOCK);
        } catch (Exception e) {
            log("already running");
            System.exit(0);
        }
        log("UsbEth base network starting");
        Looper.prepareMainLooper();

        Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "connectivity");
        Class<?> icmC = Class.forName("android.net.IConnectivityManager");
        Object icm = Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);

        Class<?> atC = Class.forName("android.app.ActivityThread");
        Object at = atC.getMethod("systemMain").invoke(null);
        final Context sys = (Context) atC.getMethod("getSystemContext").invoke(at);
        // calls are checked against the calling uid (shell): present ourselves as its package
        Context ctx = new ContextWrapper(sys) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public String getAttributionTag() { return null; }
            @Override public Context getApplicationContext() { return this; }
        };

        ethMgr = sys.getSystemService("ethernet");
        ethMgr.getClass().getMethod("setIncludeTestInterfaces", boolean.class).invoke(ethMgr, true);

        IBinder tb = (IBinder) icmC.getMethod("startOrGetTestNetworkService").invoke(icm);
        Class<?> itnC = Class.forName("android.net.connectivity.android.net.ITestNetworkManager");
        Object itn = Class.forName("android.net.connectivity.android.net.ITestNetworkManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, tb);
        Class<?> tnmC = Class.forName("android.net.TestNetworkManager");
        Constructor<?> tnmK = tnmC.getDeclaredConstructor(itnC);
        tnmK.setAccessible(true);
        testNetMgr = tnmK.newInstance(itn);
        tapIface = tnmC.getMethod("createTapInterface").invoke(testNetMgr);
        String name = (String) tapIface.getClass().getMethod("getInterfaceName").invoke(tapIface);
        tapFd = (ParcelFileDescriptor) tapIface.getClass().getMethod("getFileDescriptor").invoke(tapIface);
        log("virtual Ethernet card " + name);

        Constructor<ConnectivityManager> cmK = ConnectivityManager.class.getDeclaredConstructor(Context.class, icmC);
        cmK.setAccessible(true);
        cm = cmK.newInstance(ctx, icm);
        // the Ethernet service brings the card up only while a request wants it; NOT_VPN, or the
        // VPN itself (Ethernet once it leans on the card) would satisfy it and the card would drop
        NetworkRequest req = new NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.requestNetwork(req, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { log("network " + n + " up"); }
            @Override public void onLost(Network n) { log("network " + n + " lost"); }
        });

        Thread io = new Thread(() -> {
            try {
                serve(new FileInputStream(tapFd.getFileDescriptor()), new FileOutputStream(tapFd.getFileDescriptor()));
            } catch (Throwable t) {
                log("virtual card closed: " + t);
            }
            System.exit(1);
        }, "basenet-io");
        io.setDaemon(true);
        io.start();

        if (apk != null) {
            // gone with the app (uninstalled): nothing left behind until the next reboot
            final File f = new File(apk);
            Thread w = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                    if (!f.exists() && !installed()) { log("UsbEth uninstalled, exiting"); System.exit(0); }
                }
            }, "basenet-watch");
            w.setDaemon(true);
            w.start();
        }
        Looper.loop();
    }

    static boolean installed() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"pm", "path", "ch.tuko.usbeth"});
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out).contains("package:");
        } catch (Exception e) {
            return true;
        }
    }

    /** Answers DHCP (one fixed lease) and ARP for the fake gateway; everything else is dropped. */
    static void serve(FileInputStream in, FileOutputStream out) throws Exception {
        byte[] f = new byte[2048];
        while (true) {
            int n = in.read(f);
            if (n < 14) { if (n < 0) throw new java.io.EOFException(); continue; }
            int et = ((f[12] & 255) << 8) | (f[13] & 255);
            if (et == 0x0806) { arp(f, n, out); continue; }
            if (et == 0x0800) dhcp(f, n, out);
        }
    }

    static void arp(byte[] f, int n, FileOutputStream out) throws Exception {
        if (n < 42 || f[21] != 1) return;                       // request
        for (int i = 0; i < 4; i++) if (f[38 + i] != GW[i]) return;
        byte[] r = new byte[42];
        System.arraycopy(f, 6, r, 0, 6);
        System.arraycopy(GW_MAC, 0, r, 6, 6);
        r[12] = 8; r[13] = 6;
        r[15] = 1; r[16] = 8; r[18] = 6; r[19] = 4; r[21] = 2;
        System.arraycopy(GW_MAC, 0, r, 22, 6);
        System.arraycopy(GW, 0, r, 28, 4);
        System.arraycopy(f, 22, r, 32, 10);                      // asker's MAC + IP become the target
        out.write(r);
    }

    static void dhcp(byte[] f, int n, FileOutputStream out) throws Exception {
        if (n < 14 + 20 + 8 + 240 || f[23] != 17) return;        // UDP
        int ihl = (f[14] & 15) * 4, udp = 14 + ihl, bo = udp + 8;
        if (bo + 240 > n || (((f[udp + 2] & 255) << 8) | (f[udp + 3] & 255)) != 67) return;
        int type = 0;
        for (int o = bo + 240; o + 1 < n && (f[o] & 255) != 255; ) {
            int k = f[o] & 255;
            if (k == 0) { o++; continue; }
            if (k == 53) type = f[o + 2];
            o += 2 + (f[o + 1] & 255);
        }
        int rt = type == 1 ? 2 : type == 3 ? 5 : 0;              // DISCOVER -> OFFER, REQUEST -> ACK
        if (rt == 0) return;

        byte[] r = new byte[14 + 20 + 8 + 300];
        int ip = 14, u = 34, bp = 42;
        System.arraycopy(f, 6, r, 0, 6);
        System.arraycopy(GW_MAC, 0, r, 6, 6);
        r[12] = 8;
        r[bp] = 2; r[bp + 1] = 1; r[bp + 2] = 6;
        System.arraycopy(f, bo + 4, r, bp + 4, 4);               // xid
        System.arraycopy(f, bo + 10, r, bp + 10, 2);             // flags
        System.arraycopy(CLIENT, 0, r, bp + 16, 4);              // yiaddr
        System.arraycopy(GW, 0, r, bp + 20, 4);                  // siaddr
        System.arraycopy(f, bo + 28, r, bp + 28, 16);            // chaddr
        int q = bp + 236;
        r[q++] = 99; r[q++] = (byte) 130; r[q++] = 83; r[q++] = 99;
        r[q++] = 53; r[q++] = 1; r[q++] = (byte) rt;
        r[q++] = 54; r[q++] = 4; System.arraycopy(GW, 0, r, q, 4); q += 4;
        r[q++] = 51; r[q++] = 4; r[q++] = 0x7f; r[q++] = (byte) 255; r[q++] = (byte) 255; r[q++] = (byte) 255;
        r[q++] = 1; r[q++] = 4; r[q++] = (byte) 255; r[q++] = (byte) 255; r[q++] = (byte) 255; r[q++] = 0;
        r[q++] = 3; r[q++] = 4; System.arraycopy(GW, 0, r, q, 4); q += 4;   // router: IpClient needs one
        r[q++] = 6; r[q++] = 4; System.arraycopy(GW, 0, r, q, 4); q += 4;   // DNS: idem
        r[q++] = (byte) 255;

        int ipLen = q - ip, udpLen = q - u;
        r[ip] = 0x45; r[ip + 2] = (byte) (ipLen >> 8); r[ip + 3] = (byte) ipLen; r[ip + 8] = 64; r[ip + 9] = 17;
        System.arraycopy(GW, 0, r, ip + 12, 4);
        r[ip + 16] = r[ip + 17] = r[ip + 18] = r[ip + 19] = (byte) 255;
        int s = 0;
        for (int i = 0; i < 20; i += 2) s += ((r[ip + i] & 255) << 8) | (r[ip + i + 1] & 255);
        while ((s >> 16) != 0) s = (s & 0xffff) + (s >> 16);
        s = ~s & 0xffff;
        r[ip + 10] = (byte) (s >> 8); r[ip + 11] = (byte) s;
        r[u + 1] = 67; r[u + 3] = 68; r[u + 4] = (byte) (udpLen >> 8); r[u + 5] = (byte) udpLen;   // checksum 0 = none
        out.write(r, 0, q);
        if (rt == 5) log("lease given");
    }
}
