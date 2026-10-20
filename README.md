# PocketGNSS (com.PocketGNSS)

Android app (Samsung Galaxy S24, Android 8.0+, target API 36) that manages **RFSAT's centimetre-accuracy GNSS
receiver** developed in the AgriBIT project (EU H2020, GA 101004259) and receives its position over **USB-OTG**
or **Bluetooth**, in the same style as TinyRAD and PocketSpectro.

The receiver (AgriBIT D3.3 §4.6): u-blox **ZED-F9P / ZED-F9R** (L1/L2/L5, RTK), an **ESP32-DevKitC V4** running
the **ESPrtk** firmware (NTRIP client, Bluetooth, web server, SD logging), an optional **Quectel BG95** LTE modem,
and an SSD1306 OLED. It outputs NMEA-0183 over micro-USB and Bluetooth, and is configured through its embedded web
server on the Wi-Fi hotspot "ESPrtk : <ID>" at 192.168.4.1. Validated accuracy (D3.5): 1 cm static / 2 cm dynamic in
RTK, < 30 cm SBAS, < 1 m autonomous; up to 20 Hz.

Two hardware versions exist and the app supports both (Setup > Receiver version):

* **LTE version** (larger): ESP32 + Quectel BG95 LTE board with nano-SIM and SD slot + ZED-F9x; the receiver's own
  NTRIP client gets corrections over the mobile network (or Wi-Fi).
* **Wi-Fi version** (compact): ESP32 + ZED-F9x only; corrections through a Wi-Fi access point, i.e. a field router,
  a 4G router or the phone's hotspot.

In both, the receiver applies the corrections itself; the app reads the corrected position. How to set up each route,
with the D3.5 timings and availability, is in **[docs/rtk-connectivity.md](docs/rtk-connectivity.md)**.

RFSAT Limited. Version 1.1.1 (build 10101).

## Connections

| Source | What it is | Notes |
|---|---|---|
| USB | **Upper micro-USB = data / COM port** (D3.3 §4.7.1): the ESP32-DevKitC's CP210x bridge, carrying the NMEA ESPrtk sends to TX_UART0 (§4.7.7), 115200 bd as in D3.5 GNSS-test-11 | Middle port = power (5 V, min 1 A); bottom port = the ZED-F9x's own CDC-ACM USB, "not normally used" but the one that takes UBX configuration directly (u-center, §4.7.22). Both are recognised by vendor ID and open the app (`res/xml/device_filter.xml`). On the ESP32 port DTR/RTS are left alone because they drive EN/IO0 and would reset it; baud 115200 or automatic search. |
| Bluetooth | Classic SPP to "BTU-ESPrtk : <ID>_<suffix>" (D3.3 §4.7.14), same NMEA as the USB port (GNSS-test-12, verified with a Galaxy S10) | Paired devices only (pair in Android settings), so only BLUETOOTH_CONNECT is needed; auto-reconnect. |
| TCP | NMEA over the network (ESPrtk Wi-Fi stream, EOS Tools Pro on port 3967, any NMEA server) | Auto-reconnect. |
| Phone GNSS | The phone's own GNSS chip (`link/PhoneGnssLink.kt`), for use without the receiver or for comparison | Android Location + GnssStatus converted to NMEA (`link/PhoneNmea.kt`: GGA, RMC, VTG, GST with Android's accuracy, GSA, GSV with L1/L5 signal IDs), so every screen works. Autonomous only: RTCM and UBX cannot be sent to the chip, so phone NTRIP, NMEA output configuration and mock location are disabled; mock positions are ignored so the app never reads back its own output. Needs location permission. |
| Simulator | Synthetic ZED-F9P (`sim/SimReceiver.kt`) | Behaves like the D3.5 results: TTFF ~26 s cold, SBAS, float, RTK fixed 25-30 s after corrections; correction link per receiver version (LTE: ~57 s to RTK data, 16-25 s outages, ~95 %; Wi-Fi: ~15 s, short drop-outs, ~99 %); static or tractor-on-swaths; answers UBX VALSET/VALGET/RST/MON-VER. Every screen and tool works with it (and Play review needs it). |

## Screens

| Tab | Purpose |
|---|---|
| Home | Connection card with quick actions, receiver version (asked once if not set), receiver module (from UBX MON-VER), description, RFSAT / AgriBIT credits, Claude AI acknowledgement |
| Live | Source name with a Disconnect button, fix banner coloured by fix type with accuracy (GST-measured or estimated), position, height MSL / ellipsoid, satellites, DOP, speed, correction age and station, receiver version with what to check when no corrections arrive, per-constellation RTK mode (GNS), phone NTRIP status and base distance, NTRIP / Recording / Mark point buttons, sky plot and C/N0 bars |
| Map | MapLibre with Street / Satellite / Terrain (same raster sources as DBM and PocketSpectro): position coloured by fix, track coloured by fix type, saved points with names, NTRIP base station, follow / recenter / fit / mark / clear |
| Tools | Survey point · Stake out · Corrections (NTRIP) · NMEA output (UBX) · Receiver settings (web) · Time to fix · Data stream |
| Log | Recording (raw NMEA + fixes CSV, minimum fix and interval), live NMEA viewer (filter by sentence type, pause, show more, copy, share the shown lines as .nmea; last 2000 sentences, nothing saved unless shared), saved points (KML / CSV / GeoJSON), sessions (NMEA / CSV / GPX / KML, delete) |
| Setup | Connection source and its options, phone NTRIP caster, outputs (mock location, NMEA TCP server), receiver ID and device number, display units, simulator |
| Exit | Confirms, stops recording, closes the link and outputs |

### Tools

* **Survey point**: averages fixes of at least a chosen fix type; mean position, σE/σN/σU, CEP50/CEP95, 2DRMS,
  largest deviation, scatter plot; with a known reference (e.g. the EOS Arrow Gold coordinate) also bias and RMS
  error, and a pass/fail against the AgriBIT static spec (D3.5 GNSS-test-03/04/05). Save as a named point.
* **Stake out**: distance, east/north/up offsets and an arrow (north-up, or heading-up while moving) to a saved point.
* **Corrections (NTRIP)**: normally the receiver collects corrections itself: its ESPrtk NTRIP client reaches the RTK
  service through Wi-Fi to the field router (or its LTE modem), configured in its web settings (sections 4 and 7);
  the Live tab shows the resulting correction age and base station ID from GGA. As a fallback, the phone's NTRIP client (v1 and v2/chunked, TLS, GGA upload for network RTK) forwards
  RTCM to the receiver over USB or Bluetooth. Shows data rate, frame CRC errors, RTCM message types, the base
  station (1005/1006) and baseline length (warning above 50 km, D3.3 §4.2), and browses the caster's sourcetable
  sorted by distance, for fields without a router or SIM coverage for the receiver.
* **NMEA output**: replaces the u-center procedure of D3.3 §4.7.22. Choose messages (presets: AgriBIT default
  DTM/GGA/GLL/GNS/GSA/RMC, AGENSO GGA+RMC, full with GSV/GST), u-blox ports (I2C, UART1, USB, SPI as in the guide),
  navigation rate 1-20 Hz, and save to RAM + BBR + Flash; UBX CFG-VALSET with ACK per message, read-back with
  CFG-VALGET. Works for sure on the F9x USB port; through the ESP32 only if ESPrtk passes UBX through (the tool
  says which).
* **Receiver settings**: shows the set-up steps for the chosen version (LTE: SIM, BG96, APN in section 16; Wi-Fi:
  router or phone hotspot SSID in section 4, with a shortcut to the phone's hotspot settings; both: NTRIP in
  section 7), then joins the hotspot "ESPrtk : <ID>" (WifiNetworkSpecifier, Android 10+; or the Wi-Fi the
  phone is already on), binds the app to it and opens http(s)://192.168.4.1 in an embedded browser. Shows the
  login (RTKrover + device number), what can be changed and the warnings of D3.3 §4.7 (never create a new profile,
  logout before power-down). The ESP32's self-signed certificate is accepted only for 192.168.4.1 and only after
  the user confirms. The phone's NTRIP client pauses while on the hotspot (it has no internet).
* **Time to fix**: cold / warm / hot start with UBX CFG-RST (GNSS-only reset, the USB link stays up) or a manual
  power-cycle; times to first fix, DGNSS, float and RTK fixed; RTK re-fix times (D3.5 GNSS-test-07).
* **Data stream**: sentences per second, totals and largest gap per NMEA type, bad checksums, other bytes,
  baud rate, and the same live NMEA viewer as the Log tab (D3.5 GNSS-test-08).

### Outputs to other apps

* **Mock location**: the receiver's position (ellipsoid height, accuracy, speed, bearing) becomes the phone's GPS
  and fused location, so AGENSO's apps, field-mapping and GIS apps use the RTK fix. Select PocketGNSS once in
  Developer options > Select mock location app.
* **NMEA TCP server**: the receiver's NMEA on 127.0.0.1:10110 (or all interfaces), the role EOS Tools Pro plays on
  port 3967 in D3.3 §4.3.

With a real receiver, recording, NTRIP, mock location and the NMEA server keep running with the screen off
(foreground service `core/LinkService.kt`, type connectedDevice|location, notification with Stop).

## Code layout

```
protocol/   Nmea (NMEA 4.10/4.11 incl. signal IDs), Ubx (CFG-VALSET/VALGET/RST, MON-VER, F9 keys), Rtcm (CRC-24Q,
            1005/1006, WGS-84 geodesy), Ntrip (requests, replies, sourcetable, dechunker), StreamSplitter (NMEA/UBX/RTCM)
core/       GnssModel (epoch assembly, satellites), Statistics (CEP, 2DRMS, TTFF, rates), GnssEngine (link, parsing,
            UBX requests, recording, survey, TTFF), NtripClient, Outputs (MockLocation, NmeaServer), LinkService
link/       GnssLink, UsbSerialLink (usb-serial-for-android), BluetoothSppLink, TcpLink, SimulatedLink
sim/        SimReceiver
data/       AppSettings (SharedPreferences StateFlow), Records (points CSV, session recorder, KML/GPX/GeoJSON/CSV)
ui/         Compose screens; Buttons/Controls shared with PocketSpectro (bordered buttons, green ON / red OFF)
```

All text is in `res/values/strings.xml` (debug builds have pseudo-locales to check this).

## Build

GitHub Actions (`.github/workflows/android-ci.yml`), as for PocketSpectro: unit tests, a **debug APK** on every run
(artifact `PocketGNSS-v<version>-debug-apk`; package `com.PocketGNSS.debug`, launcher label "PocketGNSS debug", signed with the
fixed key `app/debug.keystore` so it updates in place and installs next to the Play version), then the signed Play bundle
(AAB) and release APK when the repository secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` are set (same names as the other RFSAT apps). A second job renders all screens with the simulator
(Robolectric + Roborazzi), including the Play set at 1080 × 1920 (24-bit). The Map tab is not rendered there
(MapLibre's native library does not load under Robolectric).

AGP 9.0.0, Gradle 9.7.0, Kotlin 2.3 (built into AGP), JDK 17, compileSdk / targetSdk 36, minSdk 26. Dependencies:
Compose BOM 2026.06.01, MapLibre 13.0.0, usb-serial-for-android 3.9.0 (JitPack, restricted to `com.github.mik3y`).

Versioning 1.MINOR.PATCH; only `appVersion` in `app/build.gradle.kts` is edited, the version code follows
(1*10000 + MINOR*100 + PATCH). `CHANGELOG.md` holds one paragraph per version for Play's "What's new".

Tests: `ProtocolTest` (the D3.5 recorded sentences, UBX reference frames, RTCM CRC and 1005, NTRIP, geodesy),
`SimulatorTest` (TTFF and fix progression, receiver versions, UBX round trip, reset, motion, exports), `PhoneGnssTest` (phone data to NMEA), `ScreenshotTest`.

## Google Play notes

See `docs/play-listing.md` (listing text, permissions and foreground-service declarations, Data safety) and
`docs/privacy-policy.md`.

## Not yet verified on hardware

Written from the AgriBIT deliverables and checked against the simulator; to confirm on the receiver:

1. The USB bridge chip of the upper (data) port: CP2102 on a genuine ESP32-DevKitC V4 (CH34x on some clones; both supported).
2. Whether ESPrtk forwards RTCM / UBX written over Bluetooth or the upper port to the F9x (needed only for the phone
   NTRIP fallback and for UBX configuration; the bottom u-blox port always accepts them).
3. Whether the ESPrtk web server answers on http or https at 192.168.4.1 (the guide mentions both).
4. Bluetooth PIN when pairing (try 1234 / 0000 if asked).
