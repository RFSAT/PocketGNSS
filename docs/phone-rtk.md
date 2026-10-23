# Phone RTK

The phone's own GNSS chip as an RTK rover: Android's raw measurements (GnssMeasurement API) processed on the phone
by **RTKLIB demo5**, with corrections from the same casters as the AgriBIT receiver (Metrica / HxGN SmartNet,
EUREF-IP, a local base). The app treats **every phone with a GNSS receiver as a potential client**; there is no list
of supported models. What a given phone achieves depends on its chip and antenna, and the app shows it live.
Phones **without** a GNSS receiver get a clear notice that PocketGNSS is only useful there with an external receiver.

| Stage | What | Status |
|---|---|---|
| A | Capability test (Tools > Phone raw GNSS) and recording of RINEX + GnssLogger + caster RTCM for post-processing | 1.3.0 |
| B | Real-time phone RTK: RTKLIB demo5 in native code, fed by the phone's measurements and the NTRIP relay | 1.4.0 |

## Using it

1. Setup > Receiver connection > **Phone**; **Phone RTK** is on by default; choose *Moving* or *Still on a point*.
2. Setup > NTRIP relay: a caster profile and mountpoint (**Choose** lists the caster's mountpoints). For a phone,
   network streams that send broadcast ephemerides work best (Metrica MSM_VRS / MSM_NEAR; EUREF MSM stations within
   about 20 km).
3. Connect, then switch **NTRIP on** (Live). The phone sends its own position (GGA) to the caster, the RTCM goes into
   the phone's RTK engine, and the Live tab shows the result like any receiver: fix type (single, float, fixed),
   accuracy from the filter, correction age and station, plus a *Phone RTK* line (signals, how many with carrier
   phase, AR ratio) and *Base data* (station, satellites, ephemerides).

Everything downstream works unchanged: map, survey averaging, stake-out, recording and export.
Mock location is not offered with the phone as the source: replacing the phone's GNSS provider would also stop the
measurements the engine needs.

## What decides the accuracy

* **Carrier phase** (accumulated delta range): needed for RTK float / fixed. Many phones report it only with duty
  cycling off; PocketGNSS requests full tracking (Android 12+). On older Android versions turn on *Developer options >
  Force full GNSS measurements*. Without carrier phase the engine still gives a code solution (metre level), and
  Live says so.
* **Second band** (L5 / E5a): with L1 only, float solutions and occasional fixes on short baselines; L1 + L5 fixes
  faster and more reliably.
* **Antenna and handling**: phone antennas are linearly polarised and suffer multipath. Hold the phone flat and
  still, away from the body, ideally on a pole or a ground plane; choose *Still on a point* when it does not move.
* **Constellations**: GPS, GLONASS, Galileo, BeiDou and QZSS, but GLONASS with code only. GLONASS satellites each
  use their own frequency, and between a phone and a geodetic base the GLONASS carrier phase carries channel-dependent
  biases that the float filter cannot absorb (survey receivers calibrate them). In tests the float solution sat 9.3 m
  from the phone's own track on a drive with GLONASS phase and 2.5 m without it, the same as without GLONASS at all
  (`RtkOptions.glonassPhase`). Weighting GLONASS code down made no difference: the phase is what matters.
* **Corrections**: the stream must contain broadcast ephemerides (RTCM 1019 / 1020 / 1042 / 1045 / 1046; Live warns
  when none arrive) and the base must be close (network / VRS streams, or a single station within about 20 km).

* **Direct signals**: indoors, by a window, on a balcony or near walls most signals reach the phone by reflection. A
  reflected signal is longer by a steady few to tens of metres in both code and carrier phase, so the carrier phase
  stays smooth and a float solution can claim centimetres while it sits tens of metres away. The engine measures how
  well the code fits (RMS of the double-differenced code residuals, `codeRms`): about a metre or two under open sky,
  20-45 m in a recording made beside a building with a Galaxy S24 (Metrica station 14 km away). Float and code DGNSS
  accuracies are therefore never reported better than 2 × codeRms / √n (`HonestAccuracy` in `rtk/PhoneRtk.kt`), and
  Live warns when the residuals pass 10 m.

* **Converging float**: an RTK float starts from the code, so at start-up (most of all while standing still, where
  multipath does not average out) and after an underpass it can sit 10-25 m away while claiming 3-7 m, then drift onto
  the right position within a minute. A float or code solution further from the phone's own fix than 5 m and than
  2 × (its accuracy + the phone's) is therefore not shown; the phone's fix is, until the two agree
  (`PositionCheck.preferPhone`). On a 35-minute Galaxy S24 drive this caught 60 of 1911 float epochs, all at start-up,
  after an underpass or at a stop. Fixed and PPP solutions are not affected.

## Trusting a fix

RTKLIB declares a fix as soon as one epoch passes the ambiguity ratio test. With a phone's few, noisy carrier-phase
signals such single-epoch fixes are often wrong: they show as brief "RTK fixed" flashes. PocketGNSS therefore
reports a fix only when it has held for 3 consecutive epochs with a ratio of at least 3 and at least 5 satellites
(RTKLIB is also set to need 5 satellites before fixing and 6 before holding, and to drop new satellites into the
fix gradually). Otherwise the epoch is shown as RTK float, with the float position and its larger, honest
standard deviations (`FixGate` in `rtk/PhoneRtk.kt`, unit-tested).

## Learning the phone

Each phone RTK session adds to a profile of this phone model: satellites with carrier phase per epoch, the share of
epochs with enough of them to fix, and cycle slips per satellite-minute (saved every minute and at the end). After
about 10 minutes in total the profile is "learned" and, with *Adapt to this phone* on (Setup > Phone), the engine
is tuned:

| Profile | Setting |
|---|---|
| Enough phase in fewer than 20 % of epochs | No ambiguity fixing: honest float / code solutions instead of false fixes |
| Enough phase in fewer than 60 % of epochs, or more than 0.5 slips per satellite-minute | Cautious: a fix must hold 10 epochs with ratio 4 |
| Otherwise | The defaults above |

Setup shows the profile and the verdict, and can reset it.

## PPP with Galileo HAS (no base station)

Setup > Receiver connection > Phone RTK > Corrections > **PPP (HAS)** switches the engine from RTK to precise point
positioning. There is no base: the NTRIP relay connects to a stream of state-space corrections (SSR: orbit, clock and
code-bias corrections to the broadcast ephemerides) and the engine (RTKLIB `pppos`) processes the phone's own
measurements with them.

* **Galileo HAS Internet Data Distribution**: free; register at the European GNSS Service Centre (gsc-europa.eu >
  Galileo HAS > HAS Internet Data Distribution). After approval the caster address and account come by e-mail; enter
  them as a caster profile (Setup > NTRIP relay) and choose the mountpoint it names. The stream carries RTCM 3 SSR
  1059 / 1060 (GPS) and 1242 / 1243 (Galileo) and the broadcast ephemerides 1019 / 1046.
* **IGS real-time** `SSRA…` streams (free registration at igs-ip.net): profile "IGS products (PPP)"
  (`products.igs-ip.net:2101`), Get mountpoints, pick an `SSRA…` stream, and switch on the ephemeris stream
  (`BCEP00BKG0`), since SSR streams carry no orbits. `SSRC…` streams refer to the satellites' centre of mass and are
  not usable.

Engine settings in PPP: kinematic (or static when "Still on a point" is chosen), orbits and clocks from SSR referred to
the antenna phase centre, absolute code biases, ionosphere-free combination (switching to the broadcast ionosphere
model after 30 epochs with fewer than 4 satellites on a second frequency, and back after 60 epochs with L5 / E5a on at
least 6 satellites), estimated zenith troposphere, solid-earth tides and phase wind-up, no ambiguity fixing (HAS has no phase
biases yet). Satellites without SSR corrections are left out. GLONASS and BeiDou are used only if the stream corrects
them (HAS: GPS and Galileo).

What to expect, from `PppTest` (one hour of real HAS corrections with station OBE4, replayed through the app):

| Signals | After 30 min, worst horizontal error | Mean |
|---|---|---|
| Survey receiver (GPS L1 / L2 / L5, Galileo E1 / E5a / E5b) | 0.16 m | 0.14 m |
| Dual-frequency phone (GPS L1 + L5, Galileo E1 + E5a) | 0.43 m | 0.37 m |
| L1 only (broadcast ionosphere) | 1.04 m | 0.93 m |

A phone adds more code noise and multipath than a survey antenna, so real phones land between the second and third
rows, and need 20–30 minutes under open sky with no long gaps to converge. Live shows "PPP converging" until 15
minutes have passed and the filter's horizontal standard deviation is at most 0.3 m. In NMEA, PPP is GGA quality 2
(differential), never 4 or 5.

**Reference frame.** PPP coordinates are in ITRF2020 (the frame of the orbits) at the date of observation. Inside the
ETRF area (Europe, 16°W–38°E) PocketGNSS converts them to ETRS89 (ETRF2000) with EPSG transformation 10586, the
EUREF-recommended one: in 2026 the shift is about 1 m, mostly south-west. Positions, grid coordinates, field
boundaries and national heights then match those of European RTK networks and land registries (to the few
centimetres between ETRF2000 and a country's own ETRS89 realisation).

## How it works

```
GnssMeasurementsEvent ──RawConvert (Kotlin)──> code / phase / Doppler / C/N0 per signal ─┐
                                                                                         ├─> rtkpos (RTKLIB demo5, C) ─> NMEA ─> app
NTRIP relay ──RTCM 3──> PhoneRtkLink.write ──> input_rtcm3 ─> base epoch, 1005/1006, ephemeris ┘
```

* `app/src/main/cpp/rtklib/`: RTKLIB demo5 sources (subset, BSD-2; unmodified except two marked additions in
  `ppp.c` for absolute SSR code biases; see the README there).
* `app/src/main/cpp/prtk.c`: the engine (one rover, one base, ephemeris merge, time reference from the phone's GNSS
  time so the RTCM decoder resolves weeks correctly even if the phone clock is wrong); `prtk_jni.c`: JNI bridge.
* `rtk/RtkNative.kt`, `rtk/PhoneRtk.kt` (options, solution, arrays), `link/PhoneRtkLink.kt` (Android side; the link
  accepts RTCM like a receiver, so the existing NTRIP relay feeds it and sends its GGA).
* Phone settings: kinematic or static, GPS + Galileo + GLONASS + BeiDou + QZSS, 15° mask, code/phase error ratio 300
  (phone code is noisy), continuous ambiguity resolution with ratio 3 (confirmed over 3 epochs, see above), no GLONASS ambiguity fixing
  (inter-channel biases), broadcast ionosphere, Saastamoinen troposphere, base data up to 30 s old.
* If the native engine is not available for a phone's processor, the app falls back to Android's location.

## Verification

* `RtkEngineTest` (JVM unit test, also in CI): the same C code built for the host runs one hour of a real 3.3 km
  baseline (RTKLIB test data, GSI stations 0759 / 3040, GPS L1 + L2) through the JNI bridge, the rover in the arrays
  the phone side builds and the base as an RTCM 3 byte stream (1005 + 1019 + MSM7). It must reach RTK fixed within a
  few minutes and end within 2 cm of RTKLIB's own post-processed solution (it ends within 3 mm).
* `PppTest`: Galileo HAS PPP on real data (above), the phone signal subsets, the L1-only fallback, SSR epoch times.
* `DatumTest`: ITRF2020 -> ETRF2000 against PROJ 9.8.1.
* `RawTest`: raw measurement conversion, RINEX output (checked with RTKLIB's RINEX reader).
* On phones: the Live tab's *Phone RTK* and *Base data* lines, and recordings (Tools > Phone raw GNSS) for
  post-processing comparisons.

## Analysing a recorded session

`SessionAnalysis` (app/src/test) replays a phone raw session (gnss_log.txt + base.rtcm3, as Tools > Phone raw GNSS
records it) through the engine with several settings and writes per-epoch CSV files and a summary to
app/build/session/:

    ./gradlew :app:testDebugUnitTest --tests '*SessionAnalysis*' -PsessionDir=<raw_ folder> [-PsessionVariants=app,dgps]
        [-PsessionTrials="phone:lat,lon,h;other:lat,lon,h" -PsessionSlots=0,2] [-PsessionDump=E25,G10]

It reports the phone's own flags per signal (valid phase, resets, slips, half-cycle state), the phone's own fix,
the base station, solutions per variant (single, code DGNSS, RTK, per constellation, weighting) against the phone's
fix, and, for trial positions, the double-differenced code residuals per satellite. The first S24 recording showed
with it that Galileo E1 pseudoranges were being dropped (the phone sets only Galileo's own code-lock flag) and that
the RINEX file of sessions with Galileo came out empty; both are fixed in 1.12.5.

## Post-processing a recorded session (optional)

**RTKLIB demo5**: RTKCONV converts `base.rtcm3` to RINEX observation + navigation; RTKPOST with `raw.obs` as rover,
kinematic or static, frequencies L1+L2+L5, *Time interpolation of base station data* on (phone epochs are not on
whole seconds), elevation mask 15°, SNR mask about 30 dB-Hz. **MATLAB**: Google's GNSS Measurement Tools read
`gnss_log.txt` directly.
