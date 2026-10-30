# UsbEth

**USB Ethernet for Android TV, without kernel drivers and without root.**

Many Android TV / Google TV sets ship a kernel without the `r8152`, `ax88179_178a` or
`cdc_ether` drivers, so plugging a USB Ethernet adapter does nothing. UsbEth drives the adapter
from user space through the standard Android USB Host API and hands the traffic to Android
through a VPN interface. The result: a gigabit-class wired connection on a TV whose built-in
port is often limited to 100 Mbit/s.

Written by **Renaud Schweingruber**. Free software under the
[GNU General Public License v3.0 or later](LICENSE).

## Features

- Works with any adapter exposing a standard **CDC-ECM** USB configuration: Realtek RTL8152 /
  RTL8153 / RTL8156, ASIX AX88179B, and many more (see [tested adapters](docs/ADAPTERS.md)).
- **250-290 Mbit/s download, 340-390 Mbit/s upload** measured on a Philips OLED950 (the TV's
  CPU is the limit), versus 100 Mbit/s for its built-in port.
- Built-in **DHCP client** (with renewal) and **ARP**, no configuration needed.
- Waits for the Ethernet **link** before DHCP, filtering adapters' stale link reports.
- With several adapters plugged in, picks the one that has a cable (*experimental, not yet
  tested on hardware*).
- Detects a USB 3.0 adapter that came up at **USB 2.0** and tells the user how to fix it.
- **Starts at boot** and when an adapter is plugged in.
- **Reconnects by itself after standby**: many TVs re-enumerate USB devices on wake-up; the
  bridge waits for the adapter and comes back about 15 s later.
- Optional **auto-OK** accessibility service that confirms the system USB dialog, for a
  connection that comes back at boot and after standby without touching the remote.
- IPv6 is captured and dropped instead of leaking through Wi-Fi.
- **Updates itself** from the GitHub releases, while the screen is off.

## How it works

```
 apps ──► Android network stack ──► TUN (VpnService) ──► UsbEth ──► USB bulk endpoints ──► adapter ──► LAN
                                    IPv4 packets         adds Ethernet header, ARP, DHCP
```

1. The adapter is switched to its **CDC-ECM configuration** (`UsbEcm.kt`). Raw Ethernet frames
   then flow over two bulk endpoints, one frame per USB transfer.
2. UsbEth runs its own **DHCP client** and **ARP** on these frames (`Dhcp.kt`, `Arp.kt`).
3. A **VpnService** creates a TUN interface with the leased address; all IPv4 traffic of the TV
   is routed into it, and UsbEth bridges TUN and Ethernet (`EthVpnService.kt`).
4. Throughput comes from **16 asynchronous USB requests** in flight per direction and three
   threads: USB completions, TUN writes, TUN reads.

## Requirements

- Android 11 (API 30) or later with USB host support. Developed and tested on Android 14
  (Google TV).
- A USB Ethernet adapter with a CDC-ECM configuration. Bus-powered gigabit adapters may need a
  **powered USB hub** on some TVs.
- Plug USB 3.0 adapters into a USB 3.0 port, with a firm, straight push.

## Installation

1. Enable **Developer options** and **USB/network debugging** on the TV.
2. Install the APK from the [Releases](../../releases) page, or build it (see below):
   ```
   adb connect <tv-ip>:5555
   adb install -r app-release.apk
   ```
3. Plug in the adapter with the network cable connected. The app opens by itself (or open
   **USB Ethernet** from the launcher and press **Connect**).
4. Allow access to the adapter: in the system dialog, **press Up, then OK** (the focus is on
   *Cancel* by default).
5. Accept the VPN request (once).
6. The screen shows `Connected 192.168.x.y/24 via <adapter> (USB 3.0)`.

### At every boot

Android TV's USB dialog has no "always allow" option, so **USB permission does not survive a
reboot**. About a minute after the TV starts, UsbEth shows a toast, then the permission dialog:
press **Up, then OK**. If the dialog is dismissed, it is shown again 15 s later, then a
notification lets you retry.

### After standby

On wake-up the TV re-enumerates its USB devices: the adapter disappears and comes back, and its
USB permission is lost. UsbEth stays in the foreground ("Waiting for the USB adapter"), asks for
the permission again as soon as the adapter is back, and reconnects. Without auto-OK, press
**Up, then OK** in the dialog.

### Auto-OK (optional, no remote needed)

The `USB Ethernet auto-OK` accessibility service presses OK in the system USB dialog when it asks
access to a CDC-ECM adapter for USB Ethernet, and closes the redundant "open USB Ethernet?"
dialog when access is already granted. It acts on nothing else (SystemUI windows naming both
the app and the adapter only). Android TV has no screen to enable it, so use adb, keeping the
services already enabled:

```
adb shell settings get secure enabled_accessibility_services     # e.g. other/.Service or null
adb shell settings put secure enabled_accessibility_services <previous value>:ch.tuko.usbeth/.UsbAutoOkService
adb shell settings put secure accessibility_enabled 1
```

(if the first command prints `null`, put `ch.tuko.usbeth/.UsbAutoOkService` alone). The log
shows `auto-OK service enabled`. To disable it, put back the previous value.

### Updates

While the bridge is up, the app checks the latest GitHub release 2 minutes after connecting,
then every 12 hours. A newer version is downloaded and kept only if its SHA-256 matches the
release's `SHA256SUMS` and the APK is signed with the same certificate as the installed app. It is
installed the next time the screen turns off (installing restarts the app, so the network drops
for a few seconds), and the bridge comes back by itself. `Check for updates` on the app screen
checks at once; once an update is downloaded, the button installs it right away. Android 12+
installs it without a dialog; otherwise it asks for a confirmation.

From adb: `adb shell am start -n ch.tuko.usbeth/.MainActivity --ez checkupdate true`.

## Good to know

- Android shows the connection as a **VPN** (key icon), not as "Ethernet". All apps go through
  it, including Plex, Jellyfin and Kodi. No other network (Wi-Fi or built-in Ethernet) is needed.
- Each adapter has its own MAC address, so it gets its own IP from your router. Set up a DHCP
  reservation to keep a fixed address.
- adb over the network goes through the bridge too: `adb connect <bridge-ip>:5555`.
- Detailed log: `adb logcat -s UsbEth`. The first line of each start names the version and author.

## Building

Android Studio (Ladybug or later): *File > Open* this folder, then *Build > Build APK(s)*.

Command line (JDK 17 and Android SDK 34):

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Use the **release** build: ART optimizes a debuggable app much less, and a debug build is about
twice as slow.

Release builds are signed with the key described in `~/.android-keys/usbeth.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; path overridable with the
`USBETH_SIGNING` environment variable). Without that file they fall back to the debug key, so
anyone can build the sources.

Official APKs are signed by Renaud Schweingruber. Certificate SHA-256 fingerprint:

```
30:97:4C:DB:18:38:B2:20:F5:2D:91:54:AD:78:24:A6:94:38:9A:1A:F7:3A:18:61:44:FD:7A:D5:F5:31:59:7B
```

Check a downloaded APK with `apksigner verify --print-certs UsbEth-1.0.0.apk`.

`deploy.sh` installs a new build on a TV that is using the bridge (installing kills the bridge
and adb, so the install and restart run detached on the TV):

```
TV=192.168.1.50:5555 ./deploy.sh
```

## Source files

| File | Role |
|---|---|
| `UsbEcm.kt` | ECM configuration, MAC address, link notifications, USB speed, sync and async bulk transfers |
| `EthVpnService.kt` | VpnService, adapter selection, permission at boot, reconnection after wake-up, RX/TX threads, DHCP renewal, alerts |
| `Dhcp.kt` | DHCP DISCOVER / REQUEST / renewal, OFFER / ACK parsing |
| `Arp.kt` | ARP cache, requests and replies |
| `MainActivity.kt` | USB permission, VPN consent, status screen |
| `BootReceiver.kt` | Restart at boot and after an update |
| `Updater.kt` | Update from the GitHub releases (checksum and signature checks, install) |
| `UsbAutoOkService.kt` | Optional accessibility service confirming the USB dialog |
| `UsbUtil.kt`, `Net.kt` | Adapter detection, byte and IPv4 helpers |
| `Credits.kt` | Version and author shown on screen and in the log |

## Limitations

- IPv4 only.
- CDC-ECM only: adapters with only a vendor protocol (older ASIX AX88772 / AX88179, Microchip
  LAN78xx) or only CDC-NCM are not supported, nor phones in USB tethering mode.
- The USB permission must be confirmed at every boot and wake-up (Android TV limitation, see
  above), unless auto-OK is enabled.
- If the DHCP server hands out a different address on renewal, the bridge stops and must be
  reconnected.

## License

Copyright (C) 2026 Renaud Schweingruber <renaud.schweingruber@protonmail.com>

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).
