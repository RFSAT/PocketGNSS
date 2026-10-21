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
* **Corrections**: the stream must contain broadcast ephemerides (RTCM 1019 / 1020 / 1042 / 1045 / 1046; Live warns
  when none arrive) and the base must be close (network / VRS streams, or a single station within about 20 km).

## How it works

```
GnssMeasurementsEvent ──RawConvert (Kotlin)──> code / phase / Doppler / C/N0 per signal ─┐
                                                                                         ├─> rtkpos (RTKLIB demo5, C) ─> NMEA ─> app
NTRIP relay ──RTCM 3──> PhoneRtkLink.write ──> input_rtcm3 ─> base epoch, 1005/1006, ephemeris ┘
```

* `app/src/main/cpp/rtklib/`: unmodified RTKLIB demo5 sources (subset, BSD-2; see the README there).
* `app/src/main/cpp/prtk.c`: the engine (one rover, one base, ephemeris merge, time reference from the phone's GNSS
  time so the RTCM decoder resolves weeks correctly even if the phone clock is wrong); `prtk_jni.c`: JNI bridge.
* `rtk/RtkNative.kt`, `rtk/PhoneRtk.kt` (options, solution, arrays), `link/PhoneRtkLink.kt` (Android side; the link
  accepts RTCM like a receiver, so the existing NTRIP relay feeds it and sends its GGA).
* Phone settings: kinematic or static, GPS + Galileo + GLONASS + BeiDou + QZSS, 15° mask, code/phase error ratio 300
  (phone code is noisy), continuous ambiguity resolution with ratio 3, no GLONASS ambiguity fixing
  (inter-channel biases), broadcast ionosphere, Saastamoinen troposphere, base data up to 30 s old.
* If the native engine is not available for a phone's processor, the app falls back to Android's location.

## Verification

* `RtkEngineTest` (JVM unit test, also in CI): the same C code built for the host runs one hour of a real 3.3 km
  baseline (RTKLIB test data, GSI stations 0759 / 3040, GPS L1 + L2) through the JNI bridge, the rover in the arrays
  the phone side builds and the base as an RTCM 3 byte stream (1005 + 1019 + MSM7). It must reach RTK fixed within a
  few minutes and end within 2 cm of RTKLIB's own post-processed solution (it ends within 3 mm).
* `RawTest`: raw measurement conversion, RINEX output (checked with RTKLIB's RINEX reader).
* On phones: the Live tab's *Phone RTK* and *Base data* lines, and recordings (Tools > Phone raw GNSS) for
  post-processing comparisons.

## Post-processing a recorded session (optional)

**RTKLIB demo5**: RTKCONV converts `base.rtcm3` to RINEX observation + navigation; RTKPOST with `raw.obs` as rover,
kinematic or static, frequencies L1+L2+L5, *Time interpolation of base station data* on (phone epochs are not on
whole seconds), elevation mask 15°, SNR mask about 30 dB-Hz. **MATLAB**: Google's GNSS Measurement Tools read
`gnss_log.txt` directly.
