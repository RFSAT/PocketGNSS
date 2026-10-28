/*
 * PocketGNSS phone RTK engine: a thin C layer over RTKLIB demo5 (rtkpos + RTCM 3 decoder).
 *
 * The rover is the phone (raw GNSS measurements converted to RINEX-style code/phase/Doppler in Kotlin),
 * the base is whatever the NTRIP caster streams (RTCM 3 MSM / legacy observations, 1005/1006 station
 * position, broadcast ephemeris 1019/1020/1042/1045/1046). In the PPP modes there is no base: the stream
 * carries state-space (SSR) orbit, clock and code-bias corrections (RTCM 1057-1068 GPS, 1240-1245 Galileo),
 * as Galileo HAS (Internet Data Distribution) and IGS real-time services send them, referred to the antenna
 * phase centre. One engine instance; the caller serialises calls. Plain C99 so the same code is built for Android (NDK) and tested on the host.
 */
#ifndef PRTK_H
#define PRTK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* positioning modes */
#define PRTK_MODE_SINGLE    0
#define PRTK_MODE_KINEMATIC 1
#define PRTK_MODE_STATIC    2
#define PRTK_MODE_PPP_KINEMATIC 3   /* precise point positioning with SSR corrections (Galileo HAS, IGS) */
#define PRTK_MODE_PPP_STATIC    4

/* solution status (same values as RTKLIB SOLQ_*) */
#define PRTK_NONE   0
#define PRTK_FIX    1
#define PRTK_FLOAT  2
#define PRTK_SBAS   3
#define PRTK_DGPS   4
#define PRTK_SINGLE 5
#define PRTK_PPP    6

/* systems (bit mask, same as RTKLIB SYS_*) */
#define PRTK_SYS_GPS 0x01
#define PRTK_SYS_SBS 0x02
#define PRTK_SYS_GLO 0x04
#define PRTK_SYS_GAL 0x08
#define PRTK_SYS_QZS 0x10
#define PRTK_SYS_CMP 0x20
#define PRTK_SYS_IRN 0x40

typedef struct {
    int mode;           /* PRTK_MODE_* */
    int navsys;         /* PRTK_SYS_* mask */
    double elmask_deg;  /* elevation mask */
    double snrmask;     /* minimum C/N0 (dB-Hz), 0 = none */
    int armode;         /* ambiguity resolution: 0 off, 1 continuous, 3 fix-and-hold */
    int glo_ar;         /* GLONASS ambiguity resolution (0 off: phones have inter-channel biases) */
    double eratio;      /* code / phase error ratio (phones: 300) */
    double thresar;     /* AR validation ratio */
    double maxage;      /* maximum age of base observations, s */
    int minfixsats;     /* minimum satellites with carrier phase to fix ambiguities */
    int iono_free;      /* PPP: 1 = ionosphere-free combination, 2 = ionosphere estimated (uncombined), 0 = broadcast model */
} prtk_opt_t;

typedef struct {
    int stat;           /* PRTK_NONE .. PRTK_SINGLE */
    int week;           /* GPS week and time of week of the solution */
    double tow;
    double llh[3];      /* latitude, longitude (deg), ellipsoidal height (m) */
    double xyz[3];      /* ECEF (m) */
    double enu_std[3];  /* standard deviations east, north, up (m) */
    double vel_enu[3];  /* velocity east, north, up (m/s), 0 when unknown */
    int ns;             /* satellites in the solution */
    double ratio;       /* AR ratio */
    double age;         /* age of differential (s) */
    double baseline;    /* distance to the base (m), 0 without a base */
    double geoid;       /* geoid separation (m, RTKLIB's embedded EGM96): height above sea level = h - geoid */
    double float_llh[3];   /* float solution (before ambiguity fixing): lat, lon (deg), h (m) */
    double float_std[3];   /* its standard deviations east, north, up (m) */
    int nfix;              /* consecutive epochs with fixed ambiguities */
    double code_rms;       /* RMS of the double-differenced code residuals after the update (m), 0 when none */
    int ncode;             /* how many there were */
    double l1_rms;         /* smoothed RMS of the L1 / E1 / B1 code residuals (m), 0 before the first */
    int l1_down;           /* 1 while L1 code is down-weighted for reflections */
} prtk_sol_t;

typedef struct {
    int has_pos;        /* station position received (1005/1006) */
    double xyz[3];
    double llh[3];
    int station_id;
    int nobs;           /* satellites in the last base epoch */
    int obs_week; double obs_tow; /* time of the last base epoch */
    int neph;           /* GPS/GAL/QZS/BDS ephemerides held */
    int ngeph;          /* GLONASS ephemerides held */
    long frames;        /* RTCM frames decoded */
    int nssr;           /* PPP: satellites with current SSR orbit and clock corrections */
    int ssr_week; double ssr_tow; /* time of the latest SSR clock correction */
    int ncbias;         /* satellites with SSR code biases */
} prtk_base_t;

/* default options for phones */
void prtk_default_opt(prtk_opt_t *opt);

/* (re)initialise; returns 1 on success */
int prtk_init(const prtk_opt_t *opt);

/* release everything */
void prtk_free(void);

/* GPS time reference for decoding RTCM (also taken from every rover epoch). */
void prtk_set_time(int week, double tow);

/* PPP: switch the ionosphere model (as prtk_opt_t.iono_free) without losing ephemerides or corrections; the filter
 * restarts. Used when a phone turns out to have no second frequency (or gains one). */
void prtk_set_iono(int iono_free);
/* restart the position filter; ephemerides, corrections, base position and options are kept */
void prtk_reset(void);

/* Tuning (analysis of recorded sessions, and settings that prove themselves): applied at the next prtk_init. */
#define PRTK_TUNE_MAXINNO_CODE  0   /* code innovation rejection threshold, m (RTKLIB default 30) */
#define PRTK_TUNE_MAXINNO_PHASE 1   /* phase innovation rejection threshold, m (default 5) */
#define PRTK_TUNE_IONO          2   /* 1 = ionosphere-free combination (single mode), 0 = broadcast model */
#define PRTK_TUNE_START_IFLC    3   /* 1 = the RTK filter's starting position from the ionosphere-free combination */
#define PRTK_TUNE_ERR_PHASE     4   /* phase error terms a = b, m (default 0.003) */
#define PRTK_TUNE_ELMIN         5   /* elevation mask, degrees */
#define PRTK_TUNE_ERATIO_L1     6   /* code / phase error ratio on L1 / E1 / B1 (slot 0) */
#define PRTK_TUNE_ERATIO_L5     7   /* code / phase error ratio on L5 / E5a / B2a (slot 2) */
#define PRTK_TUNE_GLO_CODE      8   /* GLONASS code error factor (inter-channel biases), default 1 */
#define PRTK_TUNE_L1_ON         9   /* L1 code down-weighting starts above this smoothed L1 code residual RMS (m); 0 = always */
#define PRTK_TUNE_L1_OFF       10   /* ... and stops below this one (m) */
#define PRTK_TUNE_N            11

/* RTK: while an epoch has at least PRTK_L5_MIN_SATS satellites with L5 / E5a / B2a code AND the L1 / E1 / B1 code shows
   reflections (its double-differenced residuals, smoothed over about PRTK_L1_TAU epochs, above PRTK_L1_ON m, until they
   fall below PRTK_L1_OFF m), L1 code errors are taken as PRTK_L1_CODE_FACTOR times larger (prtk_rover). Under open sky
   the L1 code keeps its full weight. Galaxy S24 replays against an EOS Arrow Gold: L1 residuals 4.5 m (median) under
   open sky, 5-6 m (90 %: 10-12 m) on drives, 16 m on a house roof among buildings; with 15 / 10 m the open-sky float
   is 0.43 m from the truth (0.83 m when always down-weighted) and the house roof keeps the 1.12.11 gain (5.7 m against
   16 m without down-weighting). */
#define PRTK_L1_CODE_FACTOR     10.0
#define PRTK_L5_MIN_SATS        4
#define PRTK_L1_ON             15.0
#define PRTK_L1_OFF            10.0
#define PRTK_L1_TAU             30.0
void prtk_tune(int key, double value);
/* analysis: DD code residuals of the last epoch at a trial position (see prtk.c) */
int prtk_dd_code(const double *rr, int f, int *sats, double *res, double *elev, int max);

/* feed RTCM 3 bytes from the caster; returns a bit mask: 1 = base epoch complete, 2 = ephemeris,
 * 4 = station position, 8 = SSR corrections */
int prtk_input_rtcm(const uint8_t *data, int n);

/* Ephemeris from another source (e.g. a second stream); same as prtk_input_rtcm but observations ignored. */
int prtk_input_rtcm_nav(const uint8_t *data, int n);

/*
 * One rover epoch. Arrays of n signals: system letter (G R E C J I S), satellite number, RINEX
 * band+attribute (2 chars, e.g. "1C", "5Q"), pseudorange (m, 0 = none), phase (cycles, 0 = none),
 * Doppler (Hz), C/N0 (dB-Hz), loss-of-lock indicator (RINEX LLI bits).
 * Returns the solution status (PRTK_*), and fills *sol.
 */
int prtk_rover(int week, double tow, int n, const char *sys, const int *prn, const char *codes,
               const double *P, const double *L, const float *D, const float *snr, const int *lli,
               prtk_sol_t *sol);

void prtk_base(prtk_base_t *base);

/* last message from the engine (errors, warnings) */
const char *prtk_message(void);

#ifdef __cplusplus
}
#endif
#endif
