# Tested adapters

All measurements were taken on a **Philips OLED950** (Google TV, Android 14, MediaTek SoC,
32-bit user space, 4 cores at 2 GHz) with UsbEth 1.0.0 (release build), as two 10-second TCP
transfers between the TV (`nc` from toybox) and a PC on the same LAN, on 2026-10-24.

Every adapter below exposes a standard **CDC-ECM** USB configuration next to its vendor mode,
which is what UsbEth drives. The configuration number is the one the app selects.

| Chip | Example product | VID:PID | ECM config | USB | Link | Download (Mbit/s) | Upload (Mbit/s) |
|---|---|---|---|---|---|---|---|
| Realtek RTL8153 | TP-Link UE300 | 0bda:8153 | 2 | 3.0 | 1 GbE | 284 / 288 | 355 / 391 |
| ASIX AX88179B | UGREEN CM209 | 0b95:1790 | 3 | 3.0 | 1 GbE | 275 / 287 | 352 / 375 |
| ASIX AX88179B | (second unit) | 0b95:1790 | 3 | 3.0 | 1 GbE | 263 / 287 | 377 / 390 |
| ASIX AX88179B | (third unit) | 0b95:1790 | 3 | 3.0 | 1 GbE | 241 / 277 | 331 / 391 |
| Realtek RTL8156 | 2.5 GbE adapter | 0bda:8156 | 3 | 3.0 | 2.5 GbE | 280 / 283 | 344 / 350 |
| Realtek RTL8156 | (second unit) | 0bda:8156 | 3 | 2.0 only* | 1 GbE | 270 / 279 / 282 | 290 - 294 |
| Realtek RTL8152 | CMI USB 10/100 | 0bda:8152 | 2 | 2.0 | 100 Mb | 89 / 95 | 94 |

\* This unit always fell back to USB 2.0, on every port, while the other adapters came up at
USB 3.0 through the same hub: its USB 3.0 side is most likely faulty.

## What limits throughput

- With any gigabit-class adapter at USB 3.0, the **TV's CPU** is the limit (about 250-290 Mbit/s
  download, 340-390 Mbit/s upload). The differences between adapters are within measurement noise,
  and a 2.5 GbE link brings nothing more on this TV.
- At **USB 2.0** (High Speed), upload is capped at about 290 Mbit/s. The app detects a USB 3.0
  adapter that came up at USB 2.0 and asks the user to plug it back in.
- A **debug build** is about twice as slow: always use the release build.

## Practical notes

- The UGREEN CM209 (AX88179B) was not detected at all when plugged directly into the TV: it
  needs a **powered USB hub**. The UE300 works directly.
- After the ECM configuration is selected, the AX88179B briefly reports a stale "link up"
  followed by a ~250 ms "link down" glitch. UsbEth waits for the link to stay up for 400 ms
  before starting DHCP.
- Adapters that only have a vendor protocol (older ASIX AX88772 / AX88179 without A/B, Microchip
  LAN78xx) and phones in USB tethering mode (RNDIS / NCM) are **not** supported.

## Checking an adapter before buying

On a Linux PC:

```
lsusb -v -d <vid:pid> | grep -E 'bConfigurationValue|bInterfaceClass|bInterfaceSubClass'
```

A configuration with an interface of class 2 / subclass 6 means CDC-ECM: it should work.
Subclass 13 (0x0d) is CDC-NCM, which UsbEth does not drive.
