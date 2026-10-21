# Galileo HAS test data

Galileo High Accuracy Service corrections from the Internet Data Distribution (RTCM 3 SSR 1059/1060/1242/1243 with
broadcast ephemerides 1019/1046), recorded on 17 August 2023, 02:00–03:00 GPS time, and the observations of station
OBE4 (Septentrio AsteRx4, Oberpfaffenhofen) for the same hour.

Source: [cssrlib-data](https://github.com/hirokawa/cssrlib-data), `data/doy2023-229/` (MIT License,
Copyright (c) 2022 Rui Hirokawa). The observation file is reduced for the tests: GPS and Galileo only, signals
C/L/D/S 1C 2W 5Q (GPS) and 1C 5Q 7Q (Galileo), every 5 s (loss-of-lock flags of skipped epochs kept).

Reference position of the antenna (ECEF, m): 4186704.2262, 834903.7677, 4723664.9337 (as used by cssrlib's
`test_ppprtcm.py`).

The app converts PPP results inside Europe to ETRS89 (ETRF2000), so PppTest compares against the same position in
ETRF2000 at the session epoch 2023.625: 4186704.7938, 834903.2013, 4723664.5189 (PROJ 9.8.1, EPSG 10586).
