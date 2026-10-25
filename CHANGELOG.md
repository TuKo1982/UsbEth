# Changelog

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
