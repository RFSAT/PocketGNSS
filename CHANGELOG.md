# Changelog

Each version's notes are one plain-text paragraph for Google Play's "What's new in this release" box,
under 500 characters, to paste as they are. Technical details live in the README and the commit history.

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH).

## 1.12.9 (build 11209)
With phone RTK, Live shows the phone's own fix beside the RTK position: its reported accuracy, how far it is from the RTK solution, and a warning when Android holds it on one point while the phone stands still. PDOP, HDOP and VDOP now come from the satellite geometry (VDOP and PDOP were missing). The PPP choice is named PPP (HAS / SSR): it works with Galileo HAS and with IGS SSRA streams.

## 1.12.8 (build 11208)
Phone RTK is more accurate when standing still: tested on a car roof, the height error fell from 17 m to about 2 m and the position sits within a metre of a 24-minute average. The engine now gives the code more weight and always runs in moving mode, so the "Phone is: moving / still" choice remains only for PPP. On drives it also stays closer to the phone's own track.

## 1.12.7 (build 11207)
Phone RTK no longer shows an RTK float that is still converging far from the true position, as at start-up or after an underpass (15-25 m away on a test drive while claiming 3-7 m): until it agrees with the phone's own fix, the phone's fix is shown. Indoor code solutions that jump tens of metres give way to the phone's fix the same way.

## 1.12.6 (build 11206)
Phone RTK no longer uses GLONASS carrier phase (GLONASS code still counts): its channel-by-channel biases between a phone and a base pulled RTK float positions several metres off (9 m against 2.5 m on a test drive). The map's follow mode stays on until you switch it off and shows when it is on. The float banner says decimetres to metres, matching the accuracy shown.

## 1.12.5 (build 11205)
Phone RTK uses Galileo E1 again on phones that flag it their own way (Galaxy S24), and RINEX files of sessions with Galileo are no longer empty. RTK float and code DGNSS no longer claim centimetres when the phone receives reflected signals: the accuracy follows how well the code fits, and Live warns. Recorded sessions replay from the GnssLogger file too.

## 1.12.4 (build 11204)
Fixes a crash when phone RTK restarted after a settings change (two parts of the app could release the RTK engine at the same time). The app log shows the RTK engine's messages in full.

## 1.12.3 (build 11203)
Fixes the app freezing with a blank screen after the phone locked (the map now draws in a way that cannot block the app). With the phone's own GNSS or phone RTK as the source, positions continue while the screen is off. Clearer app log: repeated engine messages are grouped and routine background closures are named as such.

## 1.12.2 (build 11202)
New App log (Tools): errors, warnings and events, including crashes and why the app last ended, to share when reporting a problem. The map trace is kept between app runs until you clear it. Fixes now say SBAS when SBAS is used, instead of DGNSS / SBAS. With phone RTK on code corrections only, the phone's own position is used when it is more accurate. Position history fits each entry on one line.

## 1.12.1 (build 11201)
Phone RTK positions no longer jump hundreds of metres: weak phone signals are left out, faulty ones are rejected, and a position far from the phone's own fix is not used (the filter restarts if that persists). Caster profiles remember their login as you type it, so the ephemeris stream connects with it. The map names the method in use, as the Live screen does. Clearer hint when a caster sends no satellite orbits.

## 1.12.0 (build 11200)
Guidance follows curves and headlands: record the first pass along a contour or bend and drive parallel curved passes, or follow passes round a field boundary inside it. Export fields, guidance lines and points as ISO-XML (ISOBUS TASKDATA) for tractor terminals and farm software. Replay can be paused. Choice buttons wrap neatly in every language.

## 1.11.0 (build 11100)
Phone PPP with Galileo HAS: positions to a few decimetres on dual-frequency phones with no base station and no subscription, using the free EU corrections (or IGS SSRA streams), given in ETRS89 within Europe. National heights: the official geoid models of 21 European countries, and EGM2008 elsewhere, on screen and in exports. A-B guidance paints a coverage map with overlaps and the field's gaps. Counts read correctly in every language.

## 1.10.0 (build 11000)
Now in Greek, German, French, Spanish, Italian, Polish and Portuguese. Points take a code, a note and a photo (KMZ export). Replay recorded sessions, also phone raw sessions through phone RTK. Offline maps: viewed tiles are kept and your own MBTiles map works without signal. Phone RTK can use a second ephemeris stream. Relay corrections to other rovers with a local NTRIP caster, and read the position from your own apps (JSON stream, broadcast).

## 1.9.0 (build 10900)
New A-B guidance tool: set a line by driving the first pass, from saved points or with a bearing, enter the implement width and follow parallel passes with a lightbar showing which way to steer and by how much, the pass number and heading error. Shift the passes to match existing tramlines, beep when off line, and see the passes on the map. Lines are saved for the next season.

## 1.8.0 (build 10800)
New Field boundary tool: walk or drive round a field, or mark its corners, to get its area and perimeter; fields show on the map and export as KML, GeoJSON, shapefile or CSV. Import points and boundaries from CSV, GeoJSON, KML, GPX or zipped shapefiles (parcels from your paying agency or GIS, also in national grids) and stake out the imported points. Points export as shapefiles too.

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
