# Changelog

Each version's notes are one plain-text paragraph for Google Play's "What's new in this release" box,
under 500 characters, to paste as they are. Technical details live in the README and the commit history.

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH).

## 1.7.0 (build 10700)
Phone RTK reports a fix only once it has held for several seconds, and learns over a few sessions how well this phone tracks carrier phase to tune itself. New fix-loss alert: sound, vibration and a notification when the fix drops or corrections stop. Grid coordinates on Live, Survey and in the points CSV: UTM across Europe and worldwide, ETRS89-LAEA and the national grids of many EU Member States.

## 1.6.0 (build 10600)
The phone can now act as a Bluetooth GNSS receiver for other devices: laptops, tablets and field controllers pair with it and receive the position as NMEA, including the RTK fix type and accuracy, from the RFSAT receiver, any external receiver or phone RTK. Set it up once in Setup; afterwards a button on the Home screen switches it on and off. It keeps running with the screen off.

## 1.5.0 (build 10500)
New Position history tool: every computed position with the method that produced it and the accuracy it reported, plotted around a reference point, with reported accuracy compared against the actual scatter, the latest positions with phone RTK details, and a CSV log. Phone RTK: each position now shows its own accuracy (it could show the previous one's), and Live names the method in use.

## 1.4.0 (build 10400)
Phone RTK: the phone's own GNSS chip can now be used as an RTK rover. Its raw measurements are combined on the phone with corrections from your NTRIP caster (RTKLIB engine), and Live shows the fix type, accuracy and how many signals carry the carrier phase that RTK needs. Accuracy depends on the phone. Phones without a GNSS receiver now get a notice that an external receiver is needed.

## 1.3.0 (build 10300)
New Phone raw GNSS tool: tests whether this phone's GNSS chip delivers the carrier phase needed for RTK, per system and band, and records static sessions for post-processing (RINEX, GnssLogger format and the caster's RTCM stream at the same time). Ready-made caster profiles for Metrica SmartNet Greece and EUREF-IP; the mountpoint list puts network streams first and sets the position reporting the caster needs.

## 1.2.0 (build 10200)
NTRIP relay through the phone: PocketGNSS fetches RTK corrections over the phone's mobile data, sends the receiver's position to the caster (needed by VRS services) and forwards the corrections over Bluetooth or USB, so the compact receiver gets RTK anywhere with mobile coverage. Caster profiles can be saved, passwords are encrypted on the phone, and Live shows how recent the corrections are.

## 1.1.1 (build 10101)
More compact screens: details start right after their labels, the connection choices fit one row (BT for Bluetooth), display options and the Live correction buttons take one row each. The map opens at a zoom where all map layers have tiles, centres on the first position, and no longer zooms past the tiles when fitting a static track.

## 1.1.0 (build 10100)
The phone's own GNSS can now be used as a source, without the RTK receiver. The Log tab shows the receiver's NMEA messages live, with filters, pause, copy and share, without saving a file. The Live tab has a Disconnect button, and the Home shortcuts no longer look like a main action.

## 1.0.0 (build 10000)
First release. Supports both RFSAT RTK GNSS receiver versions (with LTE modem, or compact Wi-Fi only) over USB, Bluetooth or TCP and shows position, fix type, accuracy, satellites and signals. Includes an NTRIP client for corrections, a map with tracks and points, recording and export (NMEA, CSV, GPX, KML), point averaging with accuracy statistics, stake-out, u-blox NMEA output set-up, the receiver's web settings, time-to-fix tests, mock location for other apps and a built-in simulator.
