# RTKLIB demo5 (subset)

Source files from RTKLIB **demo5** by rtklibexplorer (https://github.com/rtklibexplorer/RTKLIB,
branch `demo5`, commit `75a2e56`, July 2025), itself based on RTKLIB 2.4.3 by T. Takasu.
BSD 2-clause licence: see `LICENSE.txt`.

All files are unmodified except `ppp.c`, where two additions are marked "PocketGNSS": with `ppp_ssr_absbias` set
(by `../prtk.c` in PPP mode), SSR code biases are applied as absolute values, as Galileo HAS and RTCM SSR define
them, instead of relative to a reference code; and when the service sends no bias for a phone's tracking mode, the
bias of an equivalent mode on the same band is used (e.g. HAS gives GPS L2 P, receivers report L2 W). When updating,
carry these two changes over and re-run `PppTest`.

Only what the phone RTK engine (`../prtk.c`) needs: RTK filter (rtkpos, lambda), single point positioning
(pntpos), satellite orbits and clocks (ephemeris, preceph, sbas, ionex), PPP helpers (ppp, ppp_ar, tides),
RTCM 2/3 decoding and encoding (rtcm, rtcm2, rtcm3, rtcm3e), common functions (rtkcmn, geoid, trace).
To update: copy the same files from a newer demo5 release and re-run the host validation described in
`docs/phone-rtk.md`.
