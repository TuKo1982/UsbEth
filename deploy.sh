#!/bin/sh
# UsbEth - Copyright (C) 2026 Renaud Schweingruber - SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the APK on the TV through the bridge itself. Installing kills the bridge (and thus
# adb), so "pm install" and the restart run on the TV in a detached process.
#
#   TV=192.168.1.50:5555 ./deploy.sh            # release APK
#   TV=192.168.1.50:5555 APK=path/to.apk ./deploy.sh
set -e
: "${TV:?set TV to the address of the TV, e.g. TV=192.168.1.50:5555}"
APK=${APK:-app/build/outputs/apk/release/app-release.apk}
adb -s "$TV" push "$APK" /data/local/tmp/usbeth.apk
adb -s "$TV" shell "setsid sh -c 'pm install -r /data/local/tmp/usbeth.apk; sleep 2; am start -n ch.tuko.usbeth/.MainActivity --ez autoconnect true' > /data/local/tmp/usbeth-deploy.log 2>&1 < /dev/null &"
echo "installation started, waiting for the bridge to come back..."
for i in $(seq 1 30); do
  sleep 2
  if timeout 3 adb connect "$TV" 2>/dev/null | grep -q connected && timeout 5 adb -s "$TV" shell true 2>/dev/null; then
    adb -s "$TV" shell cat /data/local/tmp/usbeth-deploy.log; exit 0
  fi
done
echo "the TV did not come back within 60 s (if the adapter got a new IP, look it up on your router)"; exit 1
