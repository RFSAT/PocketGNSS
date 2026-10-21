# PocketGNSS (com.PocketGNSS)

Android app (Samsung Galaxy S24, Android 8.0+, target API 36) that manages **RFSAT's centimetre-accuracy GNSS
receiver** developed in the [AgriBIT project](https://cordis.europa.eu/project/id/101004259) (EU H2020, GA 101004259) and receives its position over **USB-OTG**
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

RFSAT Limited. Version 1.7.0 (build 10700).

## Background: the AgriBIT project

The receiver was developed by RFSAT Limited in **AgriBIT** — *Artificial Intelligence applied to Precision Farming by
the use of GNSS and Integrated Technologies*, an EU Horizon 2020 Innovation Action (grant agreement 101004259, call
SU-SPACE-EGNSS-3, 1 July 2021 – 30 June 2024, coordinated by Engineering – Ingegneria Informatica SpA). The project's
own web portal has been discontinued; its official record, with the public deliverables, is on CORDIS:
<https://cordis.europa.eu/project/id/101004259> (deliverables under
[Results](https://cordis.europa.eu/project/id/101004259/results)).

The design, implementation and validation of the GNSS receiver are described in the deliverables of **Work Package 3**:

| Deliverable | Content | Availability |
|---|---|---|
| D3.1 State of the Art in GNSS Solutions for Agriculture (M9) | GNSS market and state of the art, receiver specifications | [Public (CORDIS)](https://ec.europa.eu/research/participants/documents/downloadPublic?documentIds=080166e5ebab5626&appId=PPGMS) |
| D3.2 GNSS Baseline Prototype & Foreground Results, rev. 1 (M12) | First receiver prototype, architecture, KPIs | Consortium only |
| D3.3 GNSS Baseline Prototype & Foreground Results, rev. 2 (M24) | Final receiver: ESP_F9x (u-blox ZED-F9x), ESP32 (ESPrtk), ESP_BG95 LTE modem; assembly, power, user and configuration guide of the embedded web server, NMEA set-up | Confidential |
| D3.4 GNSS Customization Options (M30) | Receiver variants for GNSS-enabled services (with AGENSO) | [Public (CORDIS)](https://ec.europa.eu/research/participants/documents/downloadPublic?documentIds=080166e50716ec89&appId=PPGMS) |
| D3.5 Technical GNSS Validation Report (M24) | Validation against the 24 GNSS specifications: accuracy (1 cm RTK, < 30 cm SBAS, < 1 m autonomous), TTFF, 20 Hz output, Wi-Fi / LTE correction links, power | Consortium only |

References in this README and in the app to "D3.3 §…" and "D3.5 …" point to those documents. Other public AgriBIT
results related to GNSS-enabled services (*GNSS enablers and GNSS enabled services*, *Multi layered model for
agricultural GNSS enabled services*) are listed on the same CORDIS Results page.

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
| Live | Source name with a Disconnect button, fix banner coloured by fix type with accuracy (GST-measured or estimated), fix-loss alert strip, position (with grid easting / northing when a grid is chosen), height MSL / ellipsoid, satellites, DOP, speed, correction age and station, receiver version with what to check when no corrections arrive, per-constellation RTK mode (GNS), phone NTRIP status and base distance, NTRIP / Recording / Mark point buttons, sky plot and C/N0 bars |
| Map | MapLibre with Street / Satellite / Terrain (same raster sources as DBM and PocketSpectro): position coloured by fix, track coloured by fix type, saved points with names, NTRIP base station, follow / recenter / fit / mark / clear |
| Tools | Survey point · Stake out · Corrections (NTRIP) · Position history · Phone raw GNSS (RTK test) · NMEA output (UBX) · Receiver settings (web) · Time to fix · Data stream |
| Log | Recording (raw NMEA + fixes CSV, minimum fix and interval), live NMEA viewer (filter by sentence type, pause, show more, copy, share the shown lines as .nmea; last 2000 sentences, nothing saved unless shared), saved points (KML / CSV / GeoJSON), sessions (NMEA / CSV / GPX / KML; phone raw sessions as RINEX / GnssLog / RTCM / ZIP; delete) |
| Setup | Connection source and its options (Phone: Phone RTK on/off, moving or still), phone NTRIP caster, outputs (mock location, NMEA TCP server, phone as Bluetooth GNSS receiver), fix-loss alert, receiver ID and device number, display units and grid, simulator |
| Exit | Confirms, stops recording, closes the link and outputs |

### Tools

* **Survey point**: averages fixes of at least a chosen fix type; mean position, σE/σN/σU, CEP50/CEP95, 2DRMS,
  largest deviation, scatter plot; with a known reference (e.g. the EOS Arrow Gold coordinate) also bias and RMS
  error, and a pass/fail against the AgriBIT static spec (D3.5 GNSS-test-03/04/05). Save as a named point.
* **Stake out**: distance, east/north/up offsets and an arrow (north-up, or heading-up while moving) to a saved point.
* **Corrections (NTRIP)**: three routes. The receiver's own ESPrtk NTRIP client, over Wi-Fi (both versions) or its
  SIM (LTE version), configured in its web settings (sections 4 and 7). Or the **NTRIP relay through the phone**:
  PocketGNSS connects over the phone's mobile data (v1 and v2/chunked, TLS), sends the receiver's GGA to the caster
  (VRS / network services such as Metrica) and forwards the RTCM stream to the receiver over Bluetooth or USB, giving
  the compact receiver RTK anywhere with mobile coverage. The relay is built for the AgriBIT receiver but works
  the same with any receiver that accepts RTCM 3 on its USB or Bluetooth link. **Caster profiles** (saved accounts
  plus starting points checked against the live sourcetables: Metrica / HxGN SmartNet Greece `gr.nrtk.eu:7801`
  with MSM_VRS or MSM_NEAR, EUREF-IP at BKG `euref-ip.net:2101` and ROB `www.euref-ip.be:2101`, IGS-IP, RTK2go;
  VRS or single base), passwords **encrypted with an Android Keystore key**
  (`data/Secrets.kt`, AES-256-GCM, never stored in plain text). Shows state, data rate, seconds since the last RTCM,
  frame CRC errors, RTCM message types, the base station (1005/1006) and baseline length (warning above 50 km, D3.3
  §4.2); browses the caster's sourcetable (network / nearest-base streams first, then single stations by distance; picking
  one sets the GGA option from the sourcetable; non-RTCM 3 streams such as CMR are marked unusable); reconnects with a 5–30 s back-off. The Live tab shows
  the correction age and station from the receiver's GGA. See `docs/rtk-connectivity.md`.
* **Phone RTK** (Setup > Phone, on by default): the phone's own GNSS chip as an RTK rover. Its raw measurements are
  processed every epoch by **RTKLIB demo5** in native code (`app/src/main/cpp`, NDK) with the caster's RTCM 3, which
  the NTRIP relay writes to the phone link as it would to a receiver; the solution comes back as NMEA (GGA 4 / 5,
  GST from the filter), so every screen works unchanged. Any phone with a GNSS receiver is a potential client; the
  Live tab shows what this phone achieves (signals with carrier phase, AR ratio, base data). Phones without a GNSS
  receiver get a notice that only an external receiver is useful. See `docs/phone-rtk.md`.
* **Position history**: every computed position with its method (for phone RTK: RTK fixed / float, code DGNSS,
  RTKLIB single, Android location) and reported accuracy; scatter plot around a reference (mean of the best method,
  or set here), per-method table of reported accuracy against actual scatter (RMS from the reference, precision,
  share within 2 sigma), the latest positions with engine internals (satellites, signals with carrier phase, AR
  ratio), CSV export and a continuous CSV log (`core/History.kt`).
* **Phone raw GNSS (RTK test)**: stage A of phone RTK (`docs/phone-rtk.md`). Reads Android's raw measurements
  (GnssMeasurement API, full tracking on Android 12+ so duty cycling does not break the carrier phase) and reports,
  per system and band, satellites, share of usable code and carrier phase (ADR), C/N0, cycle slips, epoch gaps and
  clock discontinuities, with a verdict: no raw data, code only (DGNSS), phase on L1, or phase on L1 + L5 (best
  case). Records static sessions for post-processing: **RINEX 3.04 observations** (`raw.obs`, C/L/D/S per signal,
  checked with RTKLIB's reader), the same data in **GnssLogger format** (`gnss_log.txt`, read directly by Google's
  MATLAB GNSS Measurement Tools) and, at the same time, the caster's **RTCM base stream** (`base.rtcm3`; the caster
  gets the phone's GGA when no receiver is connected). Code in `raw/` (pure Kotlin, unit-tested; the phone RTK engine
  of stage B will reuse the converter).
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

### Phone as GNSS receiver (Bluetooth)

The phone serves NMEA over a Bluetooth Classic serial-port (SPP) service, "PocketGNSS NMEA", like a Bluetooth GNSS
receiver (`core/BtServer.kt`). Paired devices connect to it: Windows through the paired phone's outgoing COM port,
Android tablets and field controllers through their external Bluetooth GNSS setting. It carries whatever the current
source computes (RFSAT receiver, any external receiver, phone RTK), with fix type, GST accuracy and correction age;
all NMEA or position sentences only (GGA, RMC, GST, GSA, VTG, GNS, ZDA, GLL). Several devices at once, each with its
own sender queue. Home has a card: before the first set-up it opens Setup > Phone as GNSS receiver; afterwards one
button switches it on and off. Runs with the screen off (foreground service). Permissions: BLUETOOTH_CONNECT, and
BLUETOOTH_ADVERTISE only for "Make visible for pairing".

### Fix-loss alert

On a tractor nobody watches the screen. Setup > Fix-loss alert warns with an alarm tone, vibration and a
high-priority notification (also with the screen off) when the fix falls below a chosen level (RTK fixed, or RTK
float / DGNSS) for 3 s, or when corrections are older than a chosen age; a short tone when it is back. It arms only
once the chosen level has held for 5 s, so a cold start does not alarm. Live shows a red strip while it lasts.
Logic in `core/Alerts.kt` (`FixAlertLogic`, unit-tested).

### Grid coordinates

Setup > Display > Grid coordinates adds easting / northing to Live and Survey and three columns (grid, easting_m,
northing_m) to the points CSV (`protocol/Grid.kt`). European Union first, the world second:

| Grid | Where |
|---|---|
| UTM, automatic or fixed zone | ETRS89 / UTM (EPSG:25828–25838) across Europe, the national grid in e.g. Germany, Spain, Italy, Denmark, Austria, Czechia, Norway; WGS 84 / UTM (EPSG:326xx / 327xx) elsewhere, 80°S–84°N |
| ETRS89-LAEA Europe (EPSG:3035) | EU statistics and INSPIRE grids |
| National grids on ETRS89 | Belgium Lambert 2008 (3812), Estonia L-EST97 (3301), Finland TM35FIN (3067), France Lambert-93 (2154), Greece TM07 (HTRS07), Croatia HTRS96/TM (3765), Ireland ITM (2157), Lithuania LKS94 (3346), Latvia LKS-92 (3059), Poland PL-1992 (2180), Portugal PT-TM06 (3763), Sweden SWEREF 99 TM (3006), Slovenia D96/TM (3794) |
| Greece EGSA87 (EPSG:2100) | Older datum, 3-parameter shift: about 1 m, flagged in the app |

All are exact projections (Transverse Mercator by the Krüger series to n⁶, Lambert conformal conic, Lambert
azimuthal equal area), checked against PROJ to 2 mm in `GridTest`. They use the position as delivered: ETRS89 with
corrections from a European RTK network, WGS 84 / ITRF without (about 0.9 m apart in Europe in 2026), so with RTK the
grid coordinates are as good as the fix. Grids needing a datum grid file (Netherlands RD, Czech / Slovak S-JTSK,
Hungary EOV, Romania Stereo 70, UK OSGB36) are not included. A position outside the grid's area gets a warning.

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
protocol/   Grid (UTM, LAEA, national grids), Nmea (NMEA 4.10/4.11 incl. signal IDs), Ubx (CFG-VALSET/VALGET/RST, MON-VER, F9 keys), Rtcm (CRC-24Q,
            1005/1006, WGS-84 geodesy), Ntrip (requests, replies, sourcetable, dechunker), StreamSplitter (NMEA/UBX/RTCM)
core/       GnssModel (epoch assembly, satellites), Statistics (CEP, 2DRMS, TTFF, rates), GnssEngine (link, parsing,
            UBX requests, recording, survey, TTFF), NtripClient, Outputs (MockLocation, NmeaServer), LinkService,
            History (position history), BtServer (Bluetooth NMEA server), Alerts (fix-loss alert)
link/       GnssLink, UsbSerialLink (usb-serial-for-android), BluetoothSppLink, TcpLink, SimulatedLink
sim/        SimReceiver
rtk/        RtkNative (JNI), PhoneRtk (options, solution, rover arrays, FixGate, phone profile and tuning); native engine in src/main/cpp (prtk.c over RTKLIB)
raw/        RawConvert (GnssClock/GnssMeasurement -> pseudorange, phase, Doppler, RINEX codes), Rinex (3.04 obs writer),
            GnssLog (GnssLogger "Raw," format), RawAnalyzer (phone RTK capability verdict), PhoneRawLogger (Android side)
data/       AppSettings (SharedPreferences StateFlow), Records (points CSV, session recorder, KML/GPX/GeoJSON/CSV)
ui/         Compose screens; Buttons/Controls shared with PocketSpectro (bordered buttons, green ON / red OFF)
```

All text is in `res/values/strings.xml` (debug builds have pseudo-locales to check this).

## Build

GitHub Actions (`.github/workflows/android-ci.yml`), as for PocketSpectro: unit tests, then the signed **release APK**
(artifact `PocketGNSS-v<version>-apk`, for installing by hand; same signature as the Play version, so either updates
the other) and the **Google Play bundle** (AAB, artifact `PocketGNSS-v<version>-play-bundle`, with the "What's new"
text for the release notes; the R8 mapping travels inside the AAB, where Play reads it). Both need the repository
secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (same names as the other RFSAT apps);
without them the run only tests and warns. The debug build type is compiled only for the tests. A second job renders all screens with the simulator
(Robolectric + Roborazzi), including the Play set at 1080 × 1920 (24-bit). The Map tab is not rendered there
(MapLibre's native library does not load under Robolectric).

AGP 9.0.0, Gradle 9.7.0, Kotlin 2.3 (built into AGP), JDK 17, compileSdk / targetSdk 36, minSdk 26, NDK 29.0.14206865
and CMake 3.22.1 for the phone RTK engine (`libprtk.so`, arm64-v8a / armeabi-v7a / x86 / x86_64, 16 KB pages; its
symbol table is included in the AAB for Play's crash reports). The unit tests also build the engine for the host
(Linux, `cc`) and run a real RTK session through the JNI bridge (`RtkEngineTest`). Dependencies:
Compose BOM 2026.06.01, MapLibre 13.0.0, usb-serial-for-android 3.9.0 (JitPack, restricted to `com.github.mik3y`).

Versioning 1.MINOR.PATCH; only `appVersion` in `app/build.gradle.kts` is edited, the version code follows
(1*10000 + MINOR*100 + PATCH). `CHANGELOG.md` holds one paragraph per version for Play's "What's new".

Tests: `ProtocolTest` (the D3.5 recorded sentences, UBX reference frames, RTCM CRC and 1005, NTRIP, geodesy),
`SimulatorTest` (TTFF and fix progression, receiver versions, UBX round trip, reset, motion, exports), `PhoneGnssTest` (phone data to NMEA), `CasterProfileTest` (profiles, encrypted passwords), `ScreenshotTest`.

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
