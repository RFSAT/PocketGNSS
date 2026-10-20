# Changelog

Each version's notes are one plain-text paragraph for Google Play's "What's new in this release" box,
under 500 characters, to paste as they are. Technical details live in the README and the commit history.

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH).

## 1.0.0 (build 10000)
First release. Supports both RFSAT RTK GNSS receiver versions (with LTE modem, or compact Wi-Fi only) over USB, Bluetooth or TCP and shows position, fix type, accuracy, satellites and signals. Includes an NTRIP client for corrections, a map with tracks and points, recording and export (NMEA, CSV, GPX, KML), point averaging with accuracy statistics, stake-out, u-blox NMEA output set-up, the receiver's web settings, time-to-fix tests, mock location for other apps and a built-in simulator.
