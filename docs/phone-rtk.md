# Phone RTK: plan and stage A (capability test and recording)

Goal: centimetre-to-decimetre positions from the **phone's own GNSS chip**, using raw measurements (Android
GnssMeasurement API) and RTK corrections from the same casters as the AgriBIT receiver. This has to work on as many
common phones as possible, so PocketGNSS first measures what each phone delivers rather than assuming one model.

| Stage | What | Status |
|---|---|---|
| A | Capability test per phone; recording of RINEX + GnssLogger + caster RTCM for post-processing | 1.3.0 |
| B | Real-time RTK in the app: RTKLIB demo5 (C, Android NDK) fed with the phone's observations and the caster's RTCM | next |
| C | Table of tested phones with the accuracy actually reached | with field data |

## What decides whether a phone can do RTK

* **Raw measurements with the full clock bias**: required for any precise use (Android 7+; nearly all phones since 2018).
* **Carrier phase** (accumulated delta range, ADR): required for RTK. Many phones report it only with duty cycling
  off. PocketGNSS asks for full tracking on Android 12+; on older versions turn on *Developer options > Force full
  GNSS measurements*.
* **Second band** (L5 / E5a): with L1 only, RTK float is possible but a fixed solution needs short baselines and
  long static sessions; L1 + L5 gives faster and more reliable fixes.
* **Antenna and environment**: phone antennas are linearly polarised and suffer multipath; hold the phone flat,
  away from the body, or on a tripod or ground plane for tests.

## Stage A in the app: Tools > Phone raw GNSS (RTK test)

1. **Capability test**: outdoors, clear sky, phone still for 1–2 minutes. The table shows per system and band the
   satellites, the share of usable code and carrier phase, and the mean C/N0; the verdict is one of *no raw data*,
   *code only (DGNSS)*, *phase on L1*, or *phase on L1 + L5*. Warnings flag phase resets and epoch gaps (duty cycling).
2. **Recording** for post-processing (replaces a separate GnssLogger session):
   * choose a caster profile and mountpoint in Setup > NTRIP relay (for example Metrica MSM_NEAR or MSM_VRS;
     DYNG00GRC0 near Athens) and keep *Also record the caster's RTCM* on;
   * put the phone on a **surveyed point** (or next to the AgriBIT receiver / EOS Arrow Gold for a short-baseline
     comparison), flat and still, and record **20–30 minutes**;
   * Log > Sessions > "Phone raw GNSS …" shares `raw.obs` (RINEX 3.04), `gnss_log.txt` (GnssLogger format),
     `base.rtcm3` (caster stream) or everything as a ZIP.

When no receiver is connected, PocketGNSS sends the phone's own position as GGA so that VRS / nearest-base
mountpoints start streaming.

## Post-processing

**RTKLIB demo5** (rtklibexplorer):

1. RTKCONV: convert `base.rtcm3` (format RTCM 3, approximate time = session date) to RINEX observation + navigation.
   The base position comes from RTCM 1005/1006; for a VRS stream it is the virtual station near the phone.
2. RTKPOST: rover = `raw.obs`, base = converted base obs, nav = converted nav (or a BRDC file from BKG / IGS).
   Positioning mode *Kinematic* (or *Static* for a fixed point), frequencies L1+L5 if the phone has them, filter
   *Combined*, *Time interpolation of base station data* **on** (phone epochs are not on whole seconds),
   elevation mask 15°, SNR mask about 30 dB-Hz, integer ambiguity *Fix and hold* (or *Continuous* for L1 only).
3. Compare with the surveyed coordinate (Tools > Survey point averaging of the AgriBIT receiver, or the EOS result).

**MATLAB**: Google's GNSS Measurement Tools (`ProcessGnssMeasScript`, `ReadGnssLogger`) read `gnss_log.txt` as a
GnssLogger file, for pseudorange and ADR analysis without RTKLIB.

## Stage B outline (next)

* RTKLIB demo5 core (rtkpos, rtcm3, ephemeris) built with the Android NDK as a small JNI library; the Kotlin
  converter of stage A (`raw/RawConvert.kt`) feeds it phone observations every epoch, the existing NTRIP client
  feeds RTCM (base observations, station position, ephemeris).
* Output as a normal PocketGNSS source ("Phone RTK"): Live, map, survey, recording and mock location then work
  unchanged, with float / fixed status and accuracy.
* Settings derived from the stage A verdict per phone (L1 only or L1 + L5, masks, ambiguity mode).
