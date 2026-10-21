/*
 * PocketGNSS phone RTK engine: a thin C layer over RTKLIB demo5 (rtkpos + RTCM 3 decoder).
 *
 * The rover is the phone (raw GNSS measurements converted to RINEX-style code/phase/Doppler in Kotlin),
 * the base is whatever the NTRIP caster streams (RTCM 3 MSM / legacy observations, 1005/1006 station
 * position, broadcast ephemeris 1019/1020/1042/1045/1046). One engine instance; the caller serialises
 * calls. Plain C99 so the same code is built for Android (NDK) and tested on the host.
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

/* solution status (same values as RTKLIB SOLQ_*) */
#define PRTK_NONE   0
#define PRTK_FIX    1
#define PRTK_FLOAT  2
#define PRTK_SBAS   3
#define PRTK_DGPS   4
#define PRTK_SINGLE 5

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
} prtk_base_t;

/* default options for phones */
void prtk_default_opt(prtk_opt_t *opt);

/* (re)initialise; returns 1 on success */
int prtk_init(const prtk_opt_t *opt);

/* release everything */
void prtk_free(void);

/* GPS time reference for decoding RTCM (also taken from every rover epoch). */
void prtk_set_time(int week, double tow);

/* feed RTCM 3 bytes from the caster; returns a bit mask: 1 = base epoch complete, 2 = ephemeris,
 * 4 = station position */
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
