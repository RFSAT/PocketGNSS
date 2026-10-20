# Getting RTK corrections: the two receiver versions

The RFSAT AgriBIT receiver reaches centimetre accuracy only with RTK corrections from a correction service (an NTRIP
caster such as METRICA / HxGN SmartNet in Greece, EUREF-IP, or a local base station such as the EOS Arrow Gold or
DJI D-RTK2). In both hardware versions the receiver fetches the corrections **itself**, with the NTRIP client of its
ESPrtk firmware, and applies them in the u-blox ZED-F9x; PocketGNSS then only reads the corrected position over USB
or Bluetooth. The two versions differ in how that NTRIP client reaches the internet (AgriBIT D3.3 §4.6, §4.7.8).

| | LTE version (larger) | Wi-Fi version (compact) |
|---|---|---|
| Boards | ESP32-DevKitC · ESP_BG95 (Quectel LTE modem, nano-SIM, micro-SD) · ESP_F9x | ESP32-DevKitC · ESP_F9x |
| Internet for corrections | Mobile network through the SIM; Wi-Fi as an alternative | Wi-Fi only: a router in the field, a mobile-broadband (4G) router, or the phone's hotspot |
| USB ports | upper = data (NMEA, 115200 bd) · middle = power · bottom = u-blox chip | upper = data (ESP32) · lower = u-blox chip; either can supply power |
| On-board SD logging | yes (slot on the modem board) | no (record on the phone instead: PocketGNSS Log tab) |
| Antennas | GNSS (SMA-F on ESP_F9x) + LTE (SMA on ESP_BG95; never power on without it) | GNSS only |
| Time to RTK data after power-up (D3.5) | 55–60 s (mostly network registration) | shorter: no network registration (not timed separately in D3.5) |
| Re-acquisition after a lost stream (D3.5) | 16–25 s | near the 1 s spec (1.5 s measured with Wi-Fi) |
| Time in RTK-corrected mode over 1 h (D3.5 GNSS-test-13) | 95 % (embedded modem, compact LTE antenna, 3/5 signal) | 99 % via a fibre-fed router, 96 % via a mobile router |

## LTE version: corrections over the mobile network

1. Get a **data-only** nano-SIM from a local operator (4G/LTE; 2G/3G are being switched off in the EU). Disable its
   PIN. The international IoT SIMs tried in AgriBIT did not provide data; local operators' SIMs did.
2. With the receiver off, slide the 1.8 V nano-SIM into the lower part of the SIM/SD holder on the modem board (cut
   corner first, contacts towards the PCB). Fit the LTE antenna before powering on.
3. Power through the **middle** micro-USB (5 V, at least 1 A; the receiver draws about 0.25 A in RTK with LTE).
4. In PocketGNSS: Tools > Receiver settings > Join receiver hotspot (hold the receiver's top button for 5 s first),
   log in, and set:
   * section 4 *Internet – RTK online*: Internet via **Quectel BG96**; MAX time wait if fail NTRIP 30;
   * section 16 *GSM-2G-3G-4G-LTE*: the operator's **APN**, user and password (a single space where the operator
     gives none), PIN blank;
   * section 7 *NTRIP Client*: caster, port, user, password, mountpoint.

   Press **Send** after each section and check the status window; **Logout** before powering down.
5. After about a minute the LCD shows the mobile signal bars and the NTRIP data mark; PocketGNSS's Live tab shows the
   correction age and station, then RTK float and, 25–30 s later, RTK fixed.

Where a Wi-Fi router is available, section 4 can be switched to Wi-Fi on this version too; it re-connects faster.

## Wi-Fi version: corrections through an access point

1. Provide a Wi-Fi access point within range of the receiver:
   * a router at the farm or field edge (fixed line or a 4G mobile router), or
   * the **phone's mobile hotspot**: the receiver then uses the phone's data plan. D3.3 §4.2 foresees exactly this
     ("via Wi-Fi to a smartphone, and from the latter to the reference station").
2. In PocketGNSS: Tools > Receiver settings > Join receiver hotspot, log in, and set:
   * section 4: Internet via **Wi-Fi**, Number Access Point **1 AP Wi-Fi** (other options do not work), the SSID and
     password of the router or phone hotspot (factory default SSID "RFSAT"), Wi-Fi Smart Scan on, timeout 0, fixed IP 0,
     MAX time wait if fail NTRIP 30;
   * section 7 *NTRIP Client*: caster, port, user, password, mountpoint.

   Do not select Quectel BG96: there is no modem.
3. When using the phone's hotspot, finish the settings first, then leave the receiver's hotspot and turn on the phone
   hotspot (Tools > Corrections > Phone hotspot settings) with the same SSID and password: many phones cannot be a
   hotspot and a Wi-Fi client at the same time. Keep the position link on Bluetooth or USB.
4. The LCD shows the Wi-Fi bars and the NTRIP data mark; the Live tab shows the correction age and RTK fix.

## Fallback for both versions: corrections through the phone

When the receiver cannot reach the service itself (no SIM coverage, no router, no hotspot), PocketGNSS's own NTRIP
client can fetch the corrections over the phone's data and send them to the receiver over USB or Bluetooth
(Setup > Fallback: NTRIP corrections from the phone; switch on from Live or Tools > Corrections). This relies on the
receiver accepting RTCM on that link: always true on the bottom (u-blox) USB port; through the ESP32 (upper port or
Bluetooth) it depends on the ESPrtk configuration and is still to be confirmed on hardware.

## In the app

* Choose the version once: Home (when not set) or Setup > Receiver web settings > Receiver version.
* Live > Corrections shows the version and, when no corrections arrive, what to check for that version.
* Tools > Receiver settings shows the steps for the chosen version; Tools > Corrections explains the route.
* The simulator follows the chosen version: LTE takes about a minute to RTK data and has occasional 16–25 s outages
  (about 95 % RTK availability); Wi-Fi gets corrections sooner with short, rarer drop-outs (about 99 %).
