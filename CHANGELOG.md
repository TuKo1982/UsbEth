# Changelog

## 1.2.1 - 2026-10-30

- As soon as an update is downloaded and verified, a dialog offers to install it now (with the
  release notes) or later (then installed when the screen is off), plus a notification in case
  the dialog cannot open from the background. The dialog shows alone over the current app.

## 1.2.0 - 2026-10-30

- Updates from the GitHub releases: checked 2 min after the bridge is up, then every 12 h; the
  APK is kept only if its SHA-256 matches SHA256SUMS and it is signed with the installed app's
  certificate; installed when the screen turns off (no dialog on Android 12+), the bridge is
  restarted after the update. `Check for updates` / `Install update` button on the app screen.
- The service could stay stuck on "adapter selection already in progress": if the USB
  permission answer never came (dialog closed or lost, adapter re-enumerated meanwhile) or the
  adapter vanished during the selection, nothing ended it and every start request was refused.
  A selection step without an answer for 60 s is now abandoned and the service goes back to
  waiting for an adapter (rescan every 3 s); an adapter unplugged during the selection does the
  same at once; a start request takes over a selection idle for 30 s.
- Permission answers and link probes from an older selection are ignored.

## 1.1.1 - 2026-10-28

- Reconnection after standby no longer depends on the USB "attached" broadcast alone: while
  waiting, the service also looks for the adapter every 3 s (the broadcast could be missed when
  the adapter came back during the shutdown, and the bridge then stayed down).
- An adapter handed over by a late system USB dialog is ignored if it has been re-enumerated
  since (it was counted as a second adapter, then selected and failed to open).
- Once the bridge has been up, a failure (USB open, DHCP, gateway, IP change, USB errors) leads
  to a new attempt every 10 s instead of a final stop. A stop requested by the user is final.
- Link test when several adapters are plugged in: 8 s instead of 4 s (an RTL8153 needs ~4 s).
- The shutdown after an unplug no longer blocks the main thread (broadcasts, auto-OK service).
- Fixed a race at wake-up: an adapter coming back while the previous session was still shutting
  down started a new session that the old shutdown then closed (ConcurrentModificationException
  in usbeth-rx, one lost 10 s retry). A new session now waits for the shutdown to finish.
- The "frames dropped" count restarts with each session.

## 1.1.0 - 2026-10-25

- Reconnects by itself after standby: the TV re-enumerates the adapter on wake-up, the service
  now waits for it in the foreground instead of stopping, asks for the USB permission again
  and brings the bridge back (about 15 s).
- Optional `UsbAutoOkService` accessibility service (enabled with adb): confirms the system USB
  dialog for our adapter and dismisses the redundant "open USB Ethernet?" dialog, so no remote
  is needed at boot or wake-up.
- The app no longer comes to the front when the system dialog launches it while the bridge is
  already up or reconnecting.

## 1.0.0 - 2026-10-24

First public release, by Renaud Schweingruber.

- User-space CDC-ECM driver with asynchronous USB transfers (16 requests per direction).
- DHCP client with renewal and NAK handling, ARP client and responder.
- VpnService bridge, IPv4 only, IPv6 captured and dropped.
- Waits for a stable Ethernet link before DHCP.
- Picks the adapter with an Ethernet link when several are plugged in (experimental, untested).
- USB 2.0 fallback detection with toast, notification and on-screen advice.
- Start at boot with a guided USB permission request, retry and fallback notification.
- Tested with Realtek RTL8152 / RTL8153 / RTL8156 and ASIX AX88179B adapters.
