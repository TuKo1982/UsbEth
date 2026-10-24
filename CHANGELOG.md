# Changelog

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
