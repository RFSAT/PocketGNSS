/*
 * PocketGNSS phone RTK engine over RTKLIB demo5. See prtk.h.
 *
 * Data flow, as in RTKLIB's rtksvr but without its threads and streams:
 *   caster RTCM 3 -> input_rtcm3 -> base epoch (obs rcv=2), station position (rtk.rb), ephemeris (nav)
 *   phone epoch   -> obsd_t (rcv=1)  -> rtkpos(rover + latest base epoch, nav) -> solution
 * PPP modes: caster RTCM 3 -> SSR orbit / clock / code bias (nav.ssr) + ephemeris; rtkpos runs pppos on the rover.
 */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "rtklib.h"
#include "prtk.h"

static int initialised = 0;
static rtk_t rtk;
static rtcm_t rtcm;          /* caster stream (observations, station, ephemeris) */
static rtcm_t rtcm_nav;      /* optional second stream: ephemeris only */
static nav_t nav;            /* ephemeris used for positioning */
static obsd_t base[MAXOBS];
static int nbase = 0;
static long frames = 0;
static char message[1024] = "";
static double maxage = 30.0;
static int ppp = 0;          /* PPP mode: no base, SSR corrections */
static obsd_t obs[MAXOBS * 2];   /* the last epoch given to rtkpos: rover then base observations */
static int last_nobs = 0;
static double eratio_l1 = 100.0, eratio_l1_weak = 100.0; /* L1 code error ratio without / with L5 code (prtk_init) */
static double l1_on = PRTK_L1_ON, l1_off = PRTK_L1_OFF;  /* reflection thresholds for the L1 down-weighting (m) */
static double l1_ema = -1.0;                              /* smoothed L1 code residual RMS (m), < 0 before the first */
static int l1_down = 0;                                   /* L1 code down-weighted now */
static double snr_cfg = 0.0, snr_cur = 0.0;               /* configured and current C/N0 mask (dB-Hz), see prtk.h */
static int snr_adapt = 1, snr_up = 0;                     /* adaptive mask on; epochs with enough signals one step up */
static gtime_t now_t;        /* GPS time of the latest rover epoch (or prtk_set_time) */
static int nav_prune = 0;    /* ephemerides were loaded from a file: drop those not valid at the first rover epoch */
static long navmsgs = 0, navbad = 0;
static uint8_t phone_eph[MAXSAT * 4], phone_geph[NSATGLO * 2]; /* that nav entry came from the phone's messages */
static void navmsg_clear(void);
extern int ppp_ssr_absbias;  /* ppp.c */
extern int pntpos_iflc;      /* pntpos.c */
extern double glo_code_fact; /* rtkpos.c */
/* Tuning knobs (prtk_tune), applied at the next prtk_init; negative = RTKLIB / app default. */
static double tune[PRTK_TUNE_N] = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };

void prtk_tune(int key, double value) { if (key >= 0 && key < PRTK_TUNE_N) tune[key] = value; }

void prtk_default_opt(prtk_opt_t *o)
{
    o->mode = PRTK_MODE_KINEMATIC;
    o->navsys = PRTK_SYS_GPS | PRTK_SYS_GAL | PRTK_SYS_GLO | PRTK_SYS_CMP | PRTK_SYS_QZS;
    o->elmask_deg = 15.0;
    o->snrmask = 0.0;
    o->armode = ARMODE_CONT;
    o->glo_ar = 0;
    o->eratio = 100.0;   /* phone code: noisier than geodetic receivers', but it anchors ambiguities that restart often */
    o->thresar = 3.0;
    o->maxage = 30.0;
    o->minfixsats = 5;
    o->iono_free = 1;
}

static void free_nav(void)
{
    free(nav.eph); free(nav.geph);
    memset(&nav, 0, sizeof(nav));
}

static int alloc_nav(void)
{
    eph_t eph0 = {0, -1, -1};
    geph_t geph0 = {0, -1};
    int i;
    memset(&nav, 0, sizeof(nav));
    if (!(nav.eph = (eph_t *)malloc(sizeof(eph_t) * MAXSAT * 4)) ||
        !(nav.geph = (geph_t *)malloc(sizeof(geph_t) * NSATGLO * 2))) {
        free_nav();
        return 0;
    }
    for (i = 0; i < MAXSAT * 4; i++) nav.eph[i] = eph0;
    for (i = 0; i < NSATGLO * 2; i++) nav.geph[i] = geph0;
    nav.n = nav.nmax = MAXSAT * 4;
    nav.ng = nav.ngmax = NSATGLO * 2;
    return 1;
}

void prtk_free(void)
{
    if (!initialised) return;
    rtkfree(&rtk);
    free_rtcm(&rtcm);
    free_rtcm(&rtcm_nav);
    free_nav();
    nbase = 0; frames = 0;
    navmsg_clear();
    initialised = 0;
}

int prtk_init(const prtk_opt_t *o)
{
    prcopt_t opt = prcopt_default;
    int i;

    prtk_free();
    switch (o->mode) {
        case PRTK_MODE_SINGLE: opt.mode = PMODE_SINGLE; break;
        case PRTK_MODE_STATIC: opt.mode = PMODE_STATIC; break;
        case PRTK_MODE_PPP_KINEMATIC: opt.mode = PMODE_PPP_KINEMA; break;
        case PRTK_MODE_PPP_STATIC: opt.mode = PMODE_PPP_STATIC; break;
        case 5:                  opt.mode = PMODE_DGPS; break;      /* code differential (analysis) */
        default:               opt.mode = PMODE_KINEMA; break;
    }
    ppp = opt.mode >= PMODE_PPP_KINEMA;
    opt.nf = 3;                        /* L1, L2, L5 slots: phones fill L1 (+ L5) */
    opt.navsys = o->navsys;
    opt.elmin = o->elmask_deg * D2R;
    if (o->snrmask > 0) {
        opt.snrmask.ena[0] = 1;
        for (i = 0; i < 9; i++) opt.snrmask.mask[0][i] = opt.snrmask.mask[1][i] = opt.snrmask.mask[2][i] = o->snrmask;
    }
    opt.modear = o->armode;
    opt.glomodear = o->glo_ar ? GLO_ARMODE_ON : GLO_ARMODE_OFF;
    opt.bdsmodear = 1;
    opt.thresar[0] = o->thresar;
    opt.eratio[0] = opt.eratio[1] = opt.eratio[2] = o->eratio;
    opt.ionoopt = IONOOPT_BRDC;
    opt.tropopt = TROPOPT_SAAS;
    opt.dynamics = opt.mode == PMODE_KINEMA ? 1 : 0;
    opt.refpos = POSOPT_RTCM;          /* base position from RTCM 1005/1006 */
    opt.maxtdiff = o->maxage;
    opt.maxout = 5;
    opt.minlock = 0;
    opt.minfixsats = o->minfixsats > 0 ? o->minfixsats : 5;
    opt.minholdsats = opt.minfixsats + 1;
    opt.arfilter = 1;
    /* RAIM fault detection and exclusion in the single-point solution that starts and re-starts the filter: one
       phone pseudorange with a gross error (multipath, a weak signal) otherwise moves it by tens to hundreds of m */
    opt.posopt[4] = 1;
    if (tune[PRTK_TUNE_MAXINNO_CODE] >= 0) opt.maxinno[1] = tune[PRTK_TUNE_MAXINNO_CODE];
    if (tune[PRTK_TUNE_MAXINNO_PHASE] >= 0) opt.maxinno[0] = tune[PRTK_TUNE_MAXINNO_PHASE];
    if (tune[PRTK_TUNE_IONO] >= 0 && opt.mode == PMODE_SINGLE) opt.ionoopt = tune[PRTK_TUNE_IONO] >= 1 ? IONOOPT_IFLC : IONOOPT_BRDC;
    pntpos_iflc = tune[PRTK_TUNE_START_IFLC] >= 1;
    glo_code_fact = tune[PRTK_TUNE_GLO_CODE] > 0 ? tune[PRTK_TUNE_GLO_CODE] : 1.0;
    if (tune[PRTK_TUNE_ERR_PHASE] >= 0) opt.err[1] = opt.err[2] = tune[PRTK_TUNE_ERR_PHASE];
    if (tune[PRTK_TUNE_ELMIN] >= 0) opt.elmin = tune[PRTK_TUNE_ELMIN] * D2R;
    /* RTK on dual-frequency phones: L1 / E1 / B1 code carries far more multipath than L5 / E5a / B2a code (Galaxy S24
       beside an EOS Arrow Gold, 2026-10-24: code-minus-carrier 4-18 m on L1 against 1.5-3.5 m on L5), so while an epoch
       has enough L5 code and the L1 residuals show reflections, L1 code gets PRTK_L1_CODE_FACTOR less weight (prtk_rover). With the 30 dB-Hz mask
       (GnssEngine) the float there ended 2.1 m from the EOS fix instead of 22 m. L1-only phones keep the full L1 weight:
       there the code is all that anchors the ambiguities. */
    eratio_l1 = opt.eratio[0];
    eratio_l1_weak = ppp ? opt.eratio[0] : opt.eratio[0] * PRTK_L1_CODE_FACTOR;
    if (tune[PRTK_TUNE_ERATIO_L1] >= 0) opt.eratio[0] = eratio_l1 = eratio_l1_weak = tune[PRTK_TUNE_ERATIO_L1];
    l1_on = tune[PRTK_TUNE_L1_ON] >= 0 ? tune[PRTK_TUNE_L1_ON] : PRTK_L1_ON;
    l1_off = tune[PRTK_TUNE_L1_OFF] >= 0 ? tune[PRTK_TUNE_L1_OFF] : PRTK_L1_OFF;
    l1_ema = -1.0; l1_down = l1_on <= 0.0;
    snr_cfg = snr_cur = o->snrmask > 0 ? o->snrmask : 0.0; snr_up = 0;
    snr_adapt = tune[PRTK_TUNE_SNR_ADAPT] != 0.0;
    if (tune[PRTK_TUNE_ERATIO_L5] >= 0) opt.eratio[2] = tune[PRTK_TUNE_ERATIO_L5];
    if (ppp) {
        /* Float PPP: SSR orbits / clocks referred to the antenna phase centre (HAS, IGS SSRA streams), estimated
           troposphere, solid earth tides, phase wind-up. No phase biases in HAS yet, so no ambiguity fixing. */
        opt.sateph = EPHOPT_SSRAPC;
        ppp_ssr_absbias = 1;   /* HAS / RTCM SSR code biases are absolute */
        opt.ionoopt = o->iono_free == 2 ? IONOOPT_EST : o->iono_free ? IONOOPT_IFLC : IONOOPT_BRDC;
        opt.tropopt = TROPOPT_EST;
        opt.tidecorr = 1;
        opt.posopt[2] = 1;
        opt.modear = ARMODE_OFF;
        opt.dynamics = 0;
    }
    maxage = o->maxage;

    if (!alloc_nav()) { strcpy(message, "out of memory"); return 0; }
    rtkinit(&rtk, &opt);
    if (!init_rtcm(&rtcm) || !init_rtcm(&rtcm_nav)) { strcpy(message, "out of memory"); return 0; }
    nbase = 0; frames = 0; message[0] = '\0';
    navmsg_clear(); nav_prune = 0;
    initialised = 1;
    return 1;
}

/* Puts an ephemeris into the positioning nav (current set; the one it replaces becomes the previous set). Returns 1
   when it is new to the engine. */
static int store_eph(const eph_t *in, int set, int from_phone)
{
    int sat = in->sat, i = sat - 1 + MAXSAT * set;
    eph_t *cur = nav.eph + i, *prev = nav.eph + sat - 1 + MAXSAT * (2 + set);
    if (sat <= 0 || sat > MAXSAT || set < 0 || set > 1) return 0;
    if (cur->sat == sat && cur->ttr.time != 0 && in->iode == cur->iode && timediff(in->toe, cur->toe) == 0.0) return 0;
    *prev = *cur; phone_eph[i + MAXSAT * 2] = phone_eph[i];
    *cur = *in; phone_eph[i] = (uint8_t)from_phone;
    return 1;
}

static int store_geph(const geph_t *in, int from_phone)
{
    int prn;
    geph_t *cur, *prev;
    if (satsys(in->sat, &prn) != SYS_GLO || prn < 1 || prn > NSATGLO) return 0;
    cur = nav.geph + prn - 1; prev = nav.geph + prn - 1 + NSATGLO;
    if (cur->sat == in->sat && cur->tof.time != 0 && in->iode == cur->iode && timediff(in->toe, cur->toe) == 0.0) return 0;
    *prev = *cur; phone_geph[prn - 1 + NSATGLO] = phone_geph[prn - 1];
    *cur = *in; phone_geph[prn - 1] = (uint8_t)from_phone;
    return 1;
}

/* Copies a newly decoded ephemeris into the positioning nav (current set). */
static void update_eph(rtcm_t *r)
{
    int prn, sat = r->ephsat, set = r->ephset;
    if (sat <= 0) return;
    if (satsys(sat, &prn) != SYS_GLO) store_eph(r->nav.eph + sat - 1 + MAXSAT * set, set, 0);
    else if (prn >= 1 && prn <= NSATGLO) store_geph(r->nav.geph + prn - 1, 0);
}

static int input(rtcm_t *r, const uint8_t *data, int n, int use_obs)
{
    int i, j, ret, flags = 0;
    if (!initialised) return 0;
    for (i = 0; i < n; i++) {
        ret = input_rtcm3(r, data[i]);
        if (ret == 0 || ret == -1) continue;
        frames++;
        if (ret == 1 && use_obs) {          /* complete base epoch */
            nbase = 0;
            for (j = 0; j < r->obs.n && nbase < MAXOBS; j++) {
                obsd_t *o = r->obs.data + j;
                if (!(satsys(o->sat, NULL) & rtk.opt.navsys)) continue;
                base[nbase] = *o;
                base[nbase++].rcv = 2;
            }
            flags |= 1;
        } else if (ret == 2) {               /* ephemeris */
            update_eph(r);
            flags |= 2;
        } else if (ret == 5 && use_obs) {    /* station position */
            if (norm(r->sta.pos, 3) > 0.0) {
                for (j = 0; j < 3; j++) rtk.rb[j] = r->sta.pos[j];
                for (j = 3; j < 6; j++) rtk.rb[j] = 0.0;
                flags |= 4;
            }
        } else if (ret == 9) {               /* ion/utc */
            matcpy(nav.ion_gps, r->nav.ion_gps, 8, 1);
        } else if (ret == 10) {              /* SSR corrections (as rtksvr: only when the ephemeris they refer to is held) */
            for (j = 0; j < MAXSAT; j++) {
                int prn, sys, k, iode;
                if (!r->ssr[j].update) continue;
                r->ssr[j].update = 0;
                iode = r->ssr[j].iode;
                sys = satsys(j + 1, &prn);
                if (sys == SYS_GPS || sys == SYS_GAL || sys == SYS_QZS || sys == SYS_CMP) {
                    for (k = 0; k < 4 && nav.eph[j + MAXSAT * k].iode != iode; k++) ;
                    if (k == 4 && r->ssr[j].t0[0].time) {   /* orbit for an ephemeris not (yet) held: keep biases only */
                        memcpy(nav.ssr[j].cbias, r->ssr[j].cbias, sizeof(nav.ssr[j].cbias));
                        continue;
                    }
                }
                nav.ssr[j] = r->ssr[j];
            }
            flags |= 8;
        }
    }
    return flags;
}

/*
 * GPS time reference for the RTCM decoders, which must resolve week numbers and time of week. Without one they
 * fall back to the device clock; the phone's GNSS time (from its raw measurements) is better.
 */
static void set_time(gtime_t t)
{
    now_t = t;
    /* RTKLIB's "current time" (week roll-over of ephemerides, RTCM): corrected from GNSS time as rtksvr does */
    if (fabs(timediff(utc2gpst(timeget()), t)) > 60.0) timeset(gpst2utc(t));
    if (rtcm.time.time == 0 || fabs(timediff(rtcm.time, t)) > 600.0) rtcm.time = t;
    if (rtcm_nav.time.time == 0 || fabs(timediff(rtcm_nav.time, t)) > 600.0) rtcm_nav.time = t;
}

void prtk_set_time(int week, double tow) { if (initialised) set_time(gpst2time(week, tow)); }

void prtk_set_iono(int iono_free)
{
    prcopt_t o;
    int want;
    if (!initialised || !ppp) return;
    want = iono_free == 2 ? IONOOPT_EST : iono_free ? IONOOPT_IFLC : IONOOPT_BRDC;
    if (rtk.opt.ionoopt == want) return;
    /* restart the filter with the other ionosphere model; ephemerides and SSR corrections are kept */
    o = rtk.opt;
    o.ionoopt = want;
    rtkfree(&rtk);
    rtkinit(&rtk, &o);
}

/* Restarts the position filter (a solution that left the phone's own position), keeping ephemerides, SSR
   corrections, the base station and the options. */
void prtk_reset(void)
{
    prcopt_t o;
    double rb[6];
    int i;
    if (!initialised) return;
    o = rtk.opt;
    for (i = 0; i < 6; i++) rb[i] = rtk.rb[i];
    rtkfree(&rtk);
    rtkinit(&rtk, &o);
    for (i = 0; i < 6; i++) rtk.rb[i] = rb[i];
    l1_ema = -1.0; l1_down = l1_on <= 0.0;
}

int prtk_input_rtcm(const uint8_t *data, int n) { return input(&rtcm, data, n, 1); }
int prtk_input_rtcm_nav(const uint8_t *data, int n) { return input(&rtcm_nav, data, n, 0); }

static int sys_of(char c)
{
    switch (c) {
        case 'G': return SYS_GPS; case 'R': return SYS_GLO; case 'E': return SYS_GAL; case 'C': return SYS_CMP;
        case 'J': return SYS_QZS; case 'I': return SYS_IRN; case 'S': return SYS_SBS;
    }
    return 0;
}

static int cmp_obs(const void *a, const void *b)
{
    const obsd_t *p = (const obsd_t *)a, *q = (const obsd_t *)b;
    return (int)p->sat - (int)q->sat;
}

static void set_snr_mask(double m)
{
    int f, i;
    rtk.opt.snrmask.ena[0] = m > 0.0;
    for (f = 0; f < NFREQ && f < 3; f++) for (i = 0; i < 9; i++) rtk.opt.snrmask.mask[f][i] = m;
    snr_cur = m;
}

/* satellites with L1 / E1 / B1 code at or above m dB-Hz (and not known to be below the elevation mask) */
static int snr_count(const obsd_t *o, int n, double m)
{
    int i, k = 0;
    double el;
    for (i = 0; i < n; i++) {
        if (o[i].P[0] == 0.0 || o[i].SNR[0] < m) continue;
        el = rtk.ssat[o[i].sat - 1].azel[1];
        if (el != 0.0 && el < rtk.opt.elmin) continue;
        k++;
    }
    return k;
}

/* the next mask level below / above m: cfg, cfg - step, ... down to the floor, then 0 (off) */
static double snr_lower(double m) { return m - PRTK_SNR_STEP >= PRTK_SNR_FLOOR ? m - PRTK_SNR_STEP : 0.0; }
static double snr_higher(double m)
{
    double h = snr_cfg;
    while (snr_lower(h) > m) h = snr_lower(h);
    return h;
}

/* PPP keeps the configured mask (0 there); RTK lowers it while too few satellites pass, see prtk.h */
static void adapt_snr_mask(const obsd_t *o, int n)
{
    double m = snr_cur, up;
    if (ppp || !snr_adapt || snr_cfg <= 0.0) return;
    while (m > 0.0 && snr_count(o, n, m) < PRTK_SNR_MIN_SATS) m = snr_lower(m);
    if (m < snr_cur) { set_snr_mask(m); snr_up = 0; return; }
    if (snr_cur >= snr_cfg) { snr_up = 0; return; }
    up = snr_higher(snr_cur);
    if (snr_count(o, n, up) >= PRTK_SNR_MIN_SATS + 2) {
        if (++snr_up >= PRTK_SNR_UP_EPOCHS) { set_snr_mask(up); snr_up = 0; }
    } else snr_up = 0;
}

/* After prtk_nav_load: ephemerides of an earlier session that are no longer valid now are dropped (RTKLIB would not
   use them, but they would count as held, and an SSR correction could match an old IODE). */
static void prune_nav(gtime_t t)
{
    eph_t eph0 = {0, -1, -1};
    geph_t geph0 = {0, -1};
    int i;
    nav_prune = 0;
    for (i = 0; i < nav.n; i++) {
        double tmax;
        if (nav.eph[i].sat <= 0) continue;
        switch (satsys(nav.eph[i].sat, NULL)) {
            case SYS_GAL: tmax = MAXDTOE_GAL; break;
            case SYS_CMP: tmax = MAXDTOE_CMP; break;
            case SYS_QZS: tmax = MAXDTOE_QZS; break;
            default: tmax = MAXDTOE; break;
        }
        if (fabs(timediff(t, nav.eph[i].toe)) > tmax) { nav.eph[i] = eph0; phone_eph[i] = 0; }
    }
    for (i = 0; i < nav.ng; i++)
        if (nav.geph[i].sat > 0 && fabs(timediff(t, nav.geph[i].toe)) > MAXDTOE_GLO) { nav.geph[i] = geph0; phone_geph[i] = 0; }
}

/* an ephemeris for satellite sat near time t (4 h: wider than every system's validity, so only a missing one counts) */
static int has_eph(int sat, gtime_t t)
{
    int i;
    for (i = 0; i < nav.n; i++) if (nav.eph[i].sat == sat && fabs(timediff(t, nav.eph[i].toe)) < 14400.0) return 1;
    for (i = 0; i < nav.ng; i++) if (nav.geph[i].sat == sat && fabs(timediff(t, nav.geph[i].toe)) < 14400.0) return 1;
    return 0;
}

int prtk_rover(int week, double tow, int n, const char *sys, const int *prn, const char *codes,
               const double *P, const double *L, const float *D, const float *snr, const int *lli,
               prtk_sol_t *sol)
{
    obsd_t rov[MAXOBS];
    gtime_t t = gpst2time(week, tow);
    int i, j, k, nr = 0, nobs = 0, s, sat, idx;
    char obscode[3];
    uint8_t code;
    double pos[3], Q[9], dr[3];

    memset(sol, 0, sizeof(*sol));
    if (!initialised) { strcpy(message, "engine not initialised"); return PRTK_NONE; }
    set_time(t);
    if (nav_prune) prune_nav(t);

    for (i = 0; i < n; i++) {
        if (!(s = sys_of(sys[i])) || !(s & rtk.opt.navsys)) continue;
        if (s == SYS_QZS) sat = satno(s, prn[i] + 192);
        else if (s == SYS_SBS) sat = satno(s, prn[i] + 100);
        else sat = satno(s, prn[i]);
        if (sat <= 0) continue;
        obscode[0] = codes[2 * i]; obscode[1] = codes[2 * i + 1]; obscode[2] = '\0';
        code = obs2code(obscode);
        if (code == CODE_NONE) continue;
        idx = code2idx(s, code);
        if (idx < 0 || idx >= NFREQ) continue;
        for (j = 0; j < nr && rov[j].sat != sat; j++) ;
        if (j == nr) {
            if (nr >= MAXOBS) continue;
            memset(rov + nr, 0, sizeof(obsd_t));
            rov[nr].time = t; rov[nr].sat = (uint8_t)sat; rov[nr].rcv = 1;
            nr++;
        }
        if (rov[j].code[idx] != CODE_NONE) continue; /* keep the first signal of a band */
        rov[j].code[idx] = code;
        rov[j].P[idx] = P[i];
        rov[j].L[idx] = L[i];
        rov[j].D[idx] = D[i];
        rov[j].SNR[idx] = snr[i];
        rov[j].LLI[idx] = (uint8_t)lli[i];
    }
    if (nr == 0) { strcpy(message, "no usable rover signals"); return PRTK_NONE; }
    adapt_snr_mask(rov, nr);
    sol->snr_mask = snr_cur;
    for (i = k = 0; i < nr; i++) if (rov[i].P[2] != 0.0 && rov[i].SNR[2] >= rtk.opt.snrmask.mask[2][0]) k++;
    rtk.opt.eratio[0] = k >= PRTK_L5_MIN_SATS && l1_down ? eratio_l1_weak : eratio_l1;
    qsort(rov, nr, sizeof(obsd_t), cmp_obs);
    for (i = 0; i < nr; i++) obs[nobs++] = rov[i];
    /* the latest base epoch, if recent enough */
    if (!ppp && nbase > 0 && fabs(timediff(t, base[0].time)) <= maxage && norm(rtk.rb, 3) > 0.0) {
        for (i = 0; i < nbase; i++) obs[nobs++] = base[i];
    }

    rtk.neb = 0;
    last_nobs = nobs;
    rtkpos(&rtk, obs, nobs, &nav);
    if (rtk.neb > 0) {
        /* the end of the buffer (the latest messages; the app shows the last line) */
        k = rtk.neb < (int)sizeof(message) - 1 ? rtk.neb : (int)sizeof(message) - 1;
        memcpy(message, rtk.errbuf + rtk.neb - k, k); message[k] = '\0';
    } else message[0] = '\0';

    sol->stat = rtk.sol.stat;
    if (rtk.sol.stat == SOLQ_NONE) {
        /* RTKLIB says only "lack of valid sats" when the satellites have no broadcast ephemeris (a stream without
           1019/1020/1042/1045/1046 and no ephemeris stream): say so, with the count */
        int ne = 0;
        for (i = 0; i < nr; i++) if (has_eph(rov[i].sat, t)) ne++;
        /* only when missing ephemerides are the reason (not 3 satellites in view, all with one, in a tunnel) */
        if (ne < 4 && ne < nr && navmsgs > 0) {
            snprintf(message, sizeof(message), "no broadcast ephemerides yet for %d of the %d satellites in view: they are "
                     "being read from the satellites' own messages (about 30 s each)", nr - ne, nr);
        } else if (ne < 4 && ne < nr) {
            snprintf(message, sizeof(message), "no broadcast ephemerides for %d of the %d satellites in view: the correction "
                     "stream carries none (switch on the ephemeris stream: an IGS / EUREF station near you)", nr - ne, nr);
        }
        return PRTK_NONE;
    }
    sol->week = week;
    sol->tow = time2gpst(rtk.sol.time, &sol->week);
    for (i = 0; i < 3; i++) sol->xyz[i] = rtk.sol.rr[i];
    ecef2pos(rtk.sol.rr, pos);
    sol->llh[0] = pos[0] * R2D; sol->llh[1] = pos[1] * R2D; sol->llh[2] = pos[2];
    /* covariance ECEF -> ENU */
    {
        double P3[9], Qe[9];
        P3[0] = rtk.sol.qr[0]; P3[4] = rtk.sol.qr[1]; P3[8] = rtk.sol.qr[2];
        P3[1] = P3[3] = rtk.sol.qr[3];
        P3[5] = P3[7] = rtk.sol.qr[4];
        P3[2] = P3[6] = rtk.sol.qr[5];
        covenu(pos, P3, Qe);
        sol->enu_std[0] = sqrt(Qe[0] > 0 ? Qe[0] : 0);
        sol->enu_std[1] = sqrt(Qe[4] > 0 ? Qe[4] : 0);
        sol->enu_std[2] = sqrt(Qe[8] > 0 ? Qe[8] : 0);
        (void)Q;
    }
    if (norm(rtk.sol.rr + 3, 3) > 0.0) {
        double v[3];
        ecef2enu(pos, rtk.sol.rr + 3, v);
        for (i = 0; i < 3; i++) sol->vel_enu[i] = v[i];
    }
    /* float states (positions 0-2 of the filter), so a fix that is not trusted can be reported as float */
    if (rtk.nx >= 3 && norm(rtk.x, 3) > 0.0) {
        double fp[3], P3[9], Qe[9];
        int nx = rtk.nx;
        ecef2pos(rtk.x, fp);
        sol->float_llh[0] = fp[0] * R2D; sol->float_llh[1] = fp[1] * R2D; sol->float_llh[2] = fp[2];
        for (i = 0; i < 3; i++) for (k = 0; k < 3; k++) P3[i + k * 3] = rtk.P[i + k * nx];
        covenu(fp, P3, Qe);
        sol->float_std[0] = sqrt(Qe[0] > 0 ? Qe[0] : 0);
        sol->float_std[1] = sqrt(Qe[4] > 0 ? Qe[4] : 0);
        sol->float_std[2] = sqrt(Qe[8] > 0 ? Qe[8] : 0);
    }
    sol->nfix = rtk.nfix;
    /* How well the code fits the solution: a phone receiving signals by reflection (indoors, by walls) has double-
       differenced code residuals of tens of metres although its float solution claims centimetres. */
    {
        double s2 = 0.0; int nc = 0, f;
        for (i = 0; i < MAXSAT; i++) for (f = 0; f < NFREQ; f++) if (rtk.ssat[i].resp[f] != 0.0) { s2 += rtk.ssat[i].resp[f] * rtk.ssat[i].resp[f]; nc++; }
        sol->code_rms = nc > 0 ? sqrt(s2 / nc) : 0.0;
        sol->ncode = nc;
        /* L1 code alone: reflections show here first (the phone's L1 code multipath is several times its L5 one) */
        for (s2 = 0.0, nc = 0, i = 0; i < MAXSAT; i++) if (rtk.ssat[i].resp[0] != 0.0) { s2 += rtk.ssat[i].resp[0] * rtk.ssat[i].resp[0]; nc++; }
        if (nc >= 4) {
            double r = sqrt(s2 / nc);
            l1_ema = l1_ema < 0.0 ? r : l1_ema + (r - l1_ema) / PRTK_L1_TAU;
            if (l1_on > 0.0) {
                if (!l1_down && l1_ema > l1_on) l1_down = 1;
                else if (l1_down && l1_ema < l1_off) l1_down = 0;
            }
        }
        sol->l1_rms = l1_ema > 0.0 ? l1_ema : 0.0;
        sol->l1_down = l1_down;
    }
    sol->geoid = geoidh(pos);
    sol->ns = rtk.sol.ns;
    if (rtk.sol.stat == SOLQ_DGPS) {
        /* RTKLIB's count is of satellites with valid carrier phase (fewer than 4 is what makes it DGPS, often 0):
           report the satellites whose code was used, i.e. tracked by rover and base above the elevation mask */
        int nc = 0, f, ok;
        for (i = 0; i < nr; i++) {
            int sv = rov[i].sat;
            if (rtk.ssat[sv - 1].azel[1] < rtk.opt.elmin) continue;
            for (j = 0; j < nbase && base[j].sat != sv; j++) ;
            if (j == nbase) continue;
            for (ok = 0, f = 0; f < NFREQ; f++) if (rov[i].P[f] != 0.0 && base[j].P[f] != 0.0) ok = 1;
            nc += ok;
        }
        sol->ns = nc;
    }
    sol->ratio = rtk.sol.ratio;
    sol->age = rtk.sol.age;
    if (norm(rtk.rb, 3) > 0.0 && (rtk.sol.stat == SOLQ_FIX || rtk.sol.stat == SOLQ_FLOAT)) {
        for (i = 0; i < 3; i++) dr[i] = rtk.sol.rr[i] - rtk.rb[i];
        sol->baseline = norm(dr, 3);
    }
    return sol->stat;
}

void prtk_base(prtk_base_t *b)
{
    int i, n = 0, ng = 0;
    double pos[3];
    memset(b, 0, sizeof(*b));
    if (!initialised) return;
    if (norm(rtk.rb, 3) > 0.0) {
        b->has_pos = 1;
        for (i = 0; i < 3; i++) b->xyz[i] = rtk.rb[i];
        ecef2pos(rtk.rb, pos);
        b->llh[0] = pos[0] * R2D; b->llh[1] = pos[1] * R2D; b->llh[2] = pos[2];
    }
    b->station_id = rtcm.staid;
    b->nobs = nbase;
    if (nbase > 0) b->obs_tow = time2gpst(base[0].time, &b->obs_week);
    for (i = 0; i < MAXSAT * 2; i++) if (nav.eph[i].sat > 0) n++;
    for (i = 0; i < NSATGLO; i++) if (nav.geph[i].sat > 0) ng++;
    b->neph = n; b->ngeph = ng;
    for (i = 0; i < MAXSAT * 2; i++) if (nav.eph[i].sat > 0 && phone_eph[i]) b->neph_phone++;
    for (i = 0; i < NSATGLO; i++) if (nav.geph[i].sat > 0 && phone_geph[i]) b->neph_phone++;
    b->navmsgs = navmsgs; b->navbad = navbad;
    b->frames = frames;
    {
        gtime_t last = {0};
        gtime_t now = rtk.sol.time.time ? rtk.sol.time : rtcm.time;
        for (i = 0; i < MAXSAT; i++) {
            const ssr_t *s = nav.ssr + i;
            int k, nb = 0;
            for (k = 0; k < MAXCODE; k++) if (s->cbias[k] != 0.0f) nb = 1;
            b->ncbias += nb;
            if (!s->t0[0].time || !s->t0[1].time) continue;
            if (now.time && (fabs(timediff(now, s->t0[0])) > 90.0 || fabs(timediff(now, s->t0[1])) > 90.0)) continue;
            b->nssr++;
            if (timediff(s->t0[1], last) > 0) last = s->t0[1];
        }
        if (last.time) b->ssr_tow = time2gpst(last, &b->ssr_week);
    }
}

const char *prtk_message(void) { return message; }

/*
 * Analysis: double-differenced code residuals of the last epoch at a trial rover position [rr] (ECEF, m), slot 0
 * (L1 / E1 / B1I / G1) or [f]; per system the satellite of highest elevation is the reference. Troposphere and
 * ionosphere are taken as cancelled (short baseline). Writes sat numbers, residuals (m) and elevations (deg); returns
 * the count.
 */
int prtk_dd_code(const double *rr, int f, int *sats, double *res, double *elev, int max)
{
    double *rs, *dts, *var, rb[3], e[3], pos[3], azel[2], rho_r, rho_b, dr, db, ref_sd[8] = {0}, ref_el[8] = {0};
    int svh[MAXOBS * 2], i, j, nr = 0, nb = 0, n = 0, ref_i[8], sys, s;
    if (!initialised || last_nobs <= 0 || f < 0 || f >= NFREQ) return 0;
    for (i = 0; i < last_nobs && obs[i].rcv == 1; i++) nr++;
    for (; i < last_nobs && obs[i].rcv == 2; i++) nb++;
    if (nr == 0 || nb == 0) return 0;
    for (i = 0; i < 3; i++) rb[i] = rtk.rb[i];
    rs = mat(6, last_nobs); dts = mat(2, last_nobs); var = mat(1, last_nobs);
    satposs(obs[0].time, obs, last_nobs, &nav, EPHOPT_BRDC, rs, dts, var, svh);
    ecef2pos(rr, pos);
    for (s = 0; s < 8; s++) ref_i[s] = -1;
    /* single differences rover - base, then the reference per system */
    {
        double sd[MAXOBS]; int si[MAXOBS], m = 0;
        for (i = 0; i < nr; i++) {
            for (j = nr; j < nr + nb && obs[j].sat != obs[i].sat; j++) ;
            if (j == nr + nb || obs[i].P[f] == 0.0 || obs[j].P[f] == 0.0 || norm(rs + i * 6, 3) <= 0.0 || norm(rs + j * 6, 3) <= 0.0) continue;
            if ((rho_r = geodist(rs + i * 6, rr, e)) <= 0.0) continue;
            satazel(pos, e, azel);
            if (azel[1] < rtk.opt.elmin) continue;
            if ((rho_b = geodist(rs + j * 6, rb, e)) <= 0.0) continue;
            dr = obs[i].P[f] - rho_r + CLIGHT * dts[i * 2];
            db = obs[j].P[f] - rho_b + CLIGHT * dts[j * 2];
            sd[m] = dr - db; si[m] = i;
            sys = satsys(obs[i].sat, NULL);
            s = sys == SYS_GPS ? 0 : sys == SYS_GLO ? 1 : sys == SYS_GAL ? 2 : sys == SYS_CMP ? 3 : sys == SYS_QZS ? 4 : 5;
            if (azel[1] > ref_el[s]) { ref_el[s] = azel[1]; ref_sd[s] = sd[m]; ref_i[s] = i; }
            elev[m < max ? m : max - 1] = azel[1] * R2D;
            m++;
        }
        for (j = 0; j < m && n < max; j++) {
            sys = satsys(obs[si[j]].sat, NULL);
            s = sys == SYS_GPS ? 0 : sys == SYS_GLO ? 1 : sys == SYS_GAL ? 2 : sys == SYS_CMP ? 3 : sys == SYS_QZS ? 4 : 5;
            if (ref_i[s] < 0 || ref_i[s] == si[j]) continue;
            sats[n] = obs[si[j]].sat; res[n] = sd[j] - ref_sd[s]; elev[n] = elev[j]; n++;
        }
    }
    free(rs); free(dts); free(var);
    return n;
}

/* ---- Ephemerides kept between engine starts ------------------------------------------------------------------------ */

#define NAVFILE_MAGIC 0x564E4750u   /* "PGNV" */
#define NAVFILE_VERSION 1

typedef struct { uint32_t magic, version, eph_size, geph_size, n, ng; double ion_gps[8]; } navfile_t;

int prtk_nav_save(const char *path)
{
    FILE *fp;
    navfile_t h;
    int i, k = 0;
    char tmp[1024];
    if (!initialised || !path || strlen(path) > sizeof(tmp) - 5) return -1;
    memset(&h, 0, sizeof(h));
    h.magic = NAVFILE_MAGIC; h.version = NAVFILE_VERSION; h.eph_size = sizeof(eph_t); h.geph_size = sizeof(geph_t);
    for (i = 0; i < nav.n; i++) if (nav.eph[i].sat > 0) h.n++;
    for (i = 0; i < nav.ng; i++) if (nav.geph[i].sat > 0) h.ng++;
    if (h.n + h.ng == 0) return 0;            /* nothing yet: keep the previous file */
    memcpy(h.ion_gps, nav.ion_gps, sizeof(h.ion_gps));
    /* written next to the file and renamed, so an app killed while writing leaves the previous file */
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    if (!(fp = fopen(tmp, "wb"))) return -1;
    fwrite(&h, sizeof(h), 1, fp);
    for (i = 0; i < nav.n; i++) if (nav.eph[i].sat > 0) {
        int32_t idx = i; uint8_t ph = phone_eph[i];
        fwrite(&idx, sizeof(idx), 1, fp); fwrite(&ph, 1, 1, fp); fwrite(nav.eph + i, sizeof(eph_t), 1, fp); k++;
    }
    for (i = 0; i < nav.ng; i++) if (nav.geph[i].sat > 0) {
        int32_t idx = i; uint8_t ph = phone_geph[i];
        fwrite(&idx, sizeof(idx), 1, fp); fwrite(&ph, 1, 1, fp); fwrite(nav.geph + i, sizeof(geph_t), 1, fp); k++;
    }
    if (fclose(fp) != 0 || rename(tmp, path) != 0) { remove(tmp); return -1; }
    return k;
}

int prtk_nav_load(const char *path)
{
    FILE *fp;
    navfile_t h;
    uint32_t i;
    int k = 0;
    if (!initialised || !path || !(fp = fopen(path, "rb"))) return -1;
    if (fread(&h, sizeof(h), 1, fp) != 1 || h.magic != NAVFILE_MAGIC || h.version != NAVFILE_VERSION ||
        h.eph_size != sizeof(eph_t) || h.geph_size != sizeof(geph_t) || h.n > (uint32_t)nav.n || h.ng > (uint32_t)nav.ng) {
        fclose(fp); return -1;
    }
    for (i = 0; i < h.n + h.ng; i++) {
        int32_t idx; uint8_t ph;
        eph_t e; geph_t g;
        if (fread(&idx, sizeof(idx), 1, fp) != 1 || fread(&ph, 1, 1, fp) != 1) break;
        if (i < h.n) {
            if (fread(&e, sizeof(e), 1, fp) != 1) break;
            if (idx < 0 || idx >= nav.n || e.sat <= 0 || e.sat > MAXSAT || satsys(e.sat, NULL) == SYS_GLO) continue;
            if (nav.eph[idx].sat > 0) continue;      /* one already received in this session is newer */
            nav.eph[idx] = e; phone_eph[idx] = ph; k++;
        } else {
            if (fread(&g, sizeof(g), 1, fp) != 1) break;
            if (idx < 0 || idx >= nav.ng || satsys(g.sat, NULL) != SYS_GLO) continue;
            if (nav.geph[idx].sat > 0) continue;
            nav.geph[idx] = g; phone_geph[idx] = ph; k++;
        }
    }
    fclose(fp);
    if (norm(nav.ion_gps, 8) == 0.0) memcpy(nav.ion_gps, h.ion_gps, sizeof(h.ion_gps));
    nav_prune = 1;
    return k;
}

/* ---- Ephemerides from the phone's navigation messages --------------------------------------------------------------- */

/* the parts of each satellite's message collected so far, with the time each came */
typedef struct {
    uint8_t gps[4][30]; gtime_t gps_t[4];        /* GPS / QZSS LNAV subframes 1-3 and 4 (24 data bits per word) */
    uint8_t gps_cand[4][30];                     /* ... received once without valid parity, waiting for a second copy */
    uint8_t gal[5][16]; gtime_t gal_t[5];        /* Galileo I/NAV word types 1-5 (128 bits) */
    uint8_t bds[3][38]; uint32_t bds_sow[3]; gtime_t bds_t[3]; /* BeiDou D1 subframes 1-3 (300 bits, deinterleaved) */
} navparts_t;

/* GLONASS strings 1-4 (string bits 85-6) per Android svid: the slot (1-24), or 93-106 = frequency channel + 100 while
   the slot is not known */
typedef struct { uint8_t s[4][10]; gtime_t t[4]; } gloparts_t;

static navparts_t parts[MAXSAT];
static gloparts_t gloparts[128];

static void navmsg_clear(void)
{
    memset(parts, 0, sizeof(parts)); memset(gloparts, 0, sizeof(gloparts));
    memset(phone_eph, 0, sizeof(phone_eph)); memset(phone_geph, 0, sizeof(phone_geph));
    navmsgs = navbad = 0;
}

static uint32_t u32(const uint8_t *p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }

/* a received part, at most max seconds old */
static int recent(gtime_t t, double max) { return t.time != 0 && fabs(timediff(now_t, t)) <= max; }

/* an orbit that can belong to a GNSS satellite, with a reference time near now */
static int plausible(const eph_t *e, double tmax)
{
    /* semi-major axis: GPS 26 600 km, Galileo 29 600 km, BeiDou MEO 27 900 km, IGSO 42 200 km */
    return e->sat > 0 && e->A > 2.0e7 && e->A < 4.5e7 && e->e >= 0.0 && e->e < 0.1 &&
           fabs(timediff(e->toe, now_t)) <= tmax;
}

/*
 * GPS / QZSS L1 C/A subframe: 10 words of 30 bits (D1-D30). Android asks for the words "as demodulated", so the data
 * bits can be inverted (D30* of the word before = 1), and chips may deliver them already corrected. Each word is tried
 * both ways against its parity (with D29* / D30* of the word before; for the first word, whose predecessor is in the
 * previous subframe, all four, and the TLM preamble 0x8B). Some chips deliver corrected data bits with meaningless
 * parity bits (a 2017 phone: 2 % of its subframes pass): then the plain data bits are taken when the preamble is right.
 * out: 30 bytes, 24 data bits per word. Returns 2 = parity passed, 1 = plain data without parity, 0 = not a subframe.
 */
static int gps_subframe(const uint8_t *d, uint8_t *out)
{
    uint32_t w[10], prev = 0;
    int i, k, mode;
    for (i = 0; i < 10; i++) w[i] = u32(d + 4 * i) & 0x3FFFFFFFu;
    for (k = 0; k < 4; k++) {                  /* D29* D30* before the first word */
        for (mode = 0; mode < 2; mode++) {     /* 0: as transmitted, 1: data bits already corrected */
            uint8_t buf[30];
            int j;
            prev = (uint32_t)k;
            for (j = 0; j < 10; j++) {
                uint32_t x = w[j];
                if (mode == 1 && (prev & 1)) x ^= 0x3FFFFFC0u;   /* back to the transmitted polarity */
                if (!decode_word((prev << 30) | x, buf + 3 * j)) break;
                prev = w[j] & 3;
            }
            if (j == 10 && buf[0] == 0x8B) { memcpy(out, buf, 30); return 2; }
        }
    }
    for (i = 0; i < 10; i++) { out[3 * i] = (uint8_t)(w[i] >> 22); out[3 * i + 1] = (uint8_t)(w[i] >> 14); out[3 * i + 2] = (uint8_t)(w[i] >> 6); }
    return out[0] == 0x8B ? 1 : 0;
}

static int nav_gps(int sys, int svid, int sub, const uint8_t *d, int len)
{
    uint8_t sf[30], buf[150];
    int sat = satno(sys, svid), id, i, par, week;
    double tow, dt;
    navparts_t *q;
    eph_t eph = {0};
    if (sat <= 0 || len < 40 || !(par = gps_subframe(d, sf))) return -1;
    id = getbitu(sf, 43, 3);                   /* subframe ID in the HOW */
    if (id < 1 || id > 5 || (sub >= 1 && sub <= 5 && sub != id)) return -1;
    /* the HOW's time of week (start of the next subframe) is now */
    tow = time2gpst(now_t, &week);
    dt = getbitu(sf, 24, 17) * 6.0 - tow;
    if (dt > 302400.0) dt -= 604800.0; else if (dt < -302400.0) dt += 604800.0;
    if (fabs(dt) > 60.0) return -1;
    if (id == 5) return 0;                     /* almanac, not needed */
    q = parts + sat - 1;
    if (par == 1) {
        /* without parity, only data seen twice (words 3-10, which repeat every 30 s while the ephemeris holds) */
        int same = memcmp(q->gps_cand[id - 1] + 6, sf + 6, 24) == 0;
        memcpy(q->gps_cand[id - 1], sf, 30);
        if (!same) return 0;
    }
    memcpy(q->gps[id - 1], sf, 30); q->gps_t[id - 1] = now_t;
    if (id == 4 && sys == SYS_GPS && norm(nav.ion_gps, 8) == 0.0) {   /* page 18: Klobuchar ionosphere */
        double ion[8] = {0};
        memset(buf, 0, sizeof(buf)); memcpy(buf + 90, sf, 30);
        if (decode_frame(buf, sys, NULL, NULL, ion, NULL) && norm(ion, 8) > 0.0) matcpy(nav.ion_gps, ion, 8, 1);
        return 0;
    }
    if (id == 4) return 0;
    for (i = 0; i < 3; i++) if (!recent(q->gps_t[i], 120.0)) return 0;
    for (i = 0; i < 3; i++) memcpy(buf + 30 * i, q->gps[i], 30);
    if (!decode_frame(buf, sys, &eph, NULL, NULL, NULL)) return 0;
    eph.sat = sat;
    if (!plausible(&eph, 4.0 * 3600.0)) return -1;
    return store_eph(&eph, 0, 1) ? PRTK_NAVMSG_EPH : 0;
}

/*
 * Galileo I/NAV page (E1-B): even part (bits 0-113) and odd part (114-227). Checked as RTKLIB's u-blox decoder does:
 * even / odd flags, nominal page and the CRC-24Q over both parts; the word is the even part's 112 data bits and the
 * odd part's first 16.
 */
static int nav_gal(int svid, const uint8_t *d, int len)
{
    uint8_t crc[26] = {0}, word[16] = {0}, buf[112];
    int sat = satno(SYS_GAL, svid), i, type;
    navparts_t *q;
    eph_t eph = {0};
    if (sat <= 0 || len < 29) return -1;
    if (getbitu(d, 0, 1) != 0 || getbitu(d, 114, 1) != 1) return -1;
    if (getbitu(d, 1, 1) || getbitu(d, 115, 1)) return 0;      /* alert page */
    for (i = 0; i < 114; i++) setbitu(crc, 4 + i, 1, getbitu(d, i, 1));
    for (i = 0; i < 82; i++) setbitu(crc, 118 + i, 1, getbitu(d, 114 + i, 1));
    if (rtk_crc24q(crc, 25) != getbitu(d, 114 + 82, 24)) return -1;
    for (i = 0; i < 112; i++) setbitu(word, i, 1, getbitu(d, 2 + i, 1));
    for (i = 0; i < 16; i++) setbitu(word, 112 + i, 1, getbitu(d, 116 + i, 1));
    type = getbitu(word, 0, 6);
    if (type < 1 || type > 5) return 0;
    q = parts + sat - 1;
    memcpy(q->gal[type - 1], word, 16); q->gal_t[type - 1] = now_t;
    for (i = 0; i < 5; i++) if (!recent(q->gal_t[i], 120.0)) return 0;
    memset(buf, 0, sizeof(buf));
    for (i = 0; i < 5; i++) memcpy(buf + 16 * (i + 1), q->gal[i], 16);
    if (!decode_gal_inav(buf, &eph, NULL, NULL) || eph.sat != sat) return 0;
    eph.code = (1 << 0) + (1 << 9);              /* I/NAV, E1-B */
    if (!plausible(&eph, 4.0 * 3600.0)) return -1;
    return store_eph(&eph, 0, 1) ? PRTK_NAVMSG_EPH : 0;
}

/* BCH(15,11) code word (generator x^4 + x + 1) without error */
static int bch15(uint32_t c)
{
    int i;
    for (i = 14; i >= 4; i--) if (c & (1u << i)) c ^= 0x13u << (i - 4);
    return (c & 0xF) == 0;
}

/*
 * BeiDou D1 subframe: 10 words of 30 bits. Word 1 is 15 plain bits and one BCH(15,11) code word; words 2-10 are two
 * code words, interleaved bit by bit as transmitted. RTKLIB's decoder wants them de-interleaved (22 data bits, then the
 * 8 parity bits), the form some receivers deliver: both forms are recognised by their BCH parity. out: 38 bytes.
 */
static int bds_subframe(const uint8_t *d, uint8_t *out)
{
    uint32_t w[10];
    int i, b, inv;
    for (i = 0; i < 10; i++) w[i] = u32(d + 4 * i) & 0x3FFFFFFFu;
    inv = (w[0] >> 19) == (~0x712u & 0x7FF);   /* preamble 11100010010 upside down: whole subframe inverted */
    if (inv) for (i = 0; i < 10; i++) w[i] ^= 0x3FFFFFFFu;
    if ((w[0] >> 19) != 0x712) return 0;
    if (!bch15(w[0] & 0x7FFF)) return 0;
    memset(out, 0, 38);
    setbitu(out, 0, 30, w[0]);
    for (i = 1; i < 10; i++) {
        uint32_t c1 = 0, c2 = 0, x = w[i], y;
        for (b = 0; b < 15; b++) {            /* interleaved: c1 c2 c1 c2 ... from the MSB */
            c1 = (c1 << 1) | ((x >> (29 - 2 * b)) & 1);
            c2 = (c2 << 1) | ((x >> (28 - 2 * b)) & 1);
        }
        if (bch15(c1) && bch15(c2)) {
            y = ((c1 >> 4) << 19) | ((c2 >> 4) << 8) | ((c1 & 0xF) << 4) | (c2 & 0xF);
        } else {                               /* de-interleaved already: data 11 + 11, parity 4 + 4 */
            c1 = ((x >> 19) << 4) | ((x >> 4) & 0xF);
            c2 = (((x >> 8) & 0x7FF) << 4) | (x & 0xF);
            if (!(bch15(c1) && bch15(c2))) return 0;
            y = x;
        }
        setbitu(out, 30 * i, 30, y);
    }
    return 1;
}

static int nav_bds(int svid, const uint8_t *d, int len)
{
    uint8_t sf[38], buf[38 * 5];
    int sat = satno(SYS_CMP, svid), id, i;
    navparts_t *q;
    eph_t eph = {0};
    if (sat <= 0 || len < 40) return -1;
    if (svid <= 5 || svid >= 59) return 0;     /* geostationary satellites send D2 */
    if (!bds_subframe(d, sf)) return -1;
    id = getbitu(sf, 15, 3);
    if (id < 1 || id > 3) return 0;
    q = parts + sat - 1;
    memcpy(q->bds[id - 1], sf, 38); q->bds_t[id - 1] = now_t;
    q->bds_sow[id - 1] = getbitu(sf, 18, 8) << 12 | getbitu(sf, 30, 12);
    for (i = 0; i < 3; i++) if (!recent(q->bds_t[i], 60.0)) return 0;
    memset(buf, 0, sizeof(buf));
    for (i = 0; i < 3; i++) memcpy(buf + 38 * i, q->bds[i], 38);
    if (!decode_bds_d1(buf, &eph, NULL, NULL)) return 0;
    eph.sat = sat;
    if (!plausible(&eph, 6.0 * 3600.0)) return -1;
    return store_eph(&eph, 0, 1) ? PRTK_NAVMSG_EPH : 0;
}

/* GLONASS string (85 bits incl. the Hamming code, MSB first); strings 1-4 of one frame, 2 s apart, give the ephemeris */
static int nav_glo(int svid, const uint8_t *d, int len, int frq)
{
    uint8_t s[11], buf[50];
    int m, i, prn;
    gloparts_t *q;
    geph_t geph = {0};
    if (len < 11 || svid < 1 || svid > 127) return -1;
    memcpy(s, d, 11); s[10] &= 0xF8;
    if (!test_glostr(s)) return -1;
    m = getbitu(s, 1, 4);
    if (m < 1 || m > 4) return 0;
    q = gloparts + svid;
    memcpy(q->s[m - 1], s, 10); q->t[m - 1] = now_t;
    if (m != 4) return 0;
    for (i = 0; i < 3; i++) if (!recent(q->t[i], 10.0) || timediff(q->t[i + 1], q->t[i]) <= 0.0) return 0;
    memset(buf, 0, sizeof(buf));
    for (i = 0; i < 4; i++) memcpy(buf + 10 * i, q->s[i], 10);
    geph.tof = now_t;
    if (!decode_glostr(buf, &geph, NULL)) return 0;
    if (satsys(geph.sat, &prn) != SYS_GLO || (svid <= NSATGLO && prn != svid)) return 0;
    /* the frequency channel is not in strings 1-4: from the svid or the carrier frequency, else the one already known */
    if (svid >= 93 && svid <= 106) frq = svid - 100;
    if (frq >= -7 && frq <= 6) geph.frq = frq;
    else if (nav.geph[prn - 1].sat == geph.sat) geph.frq = nav.geph[prn - 1].frq;
    else return 0;
    if (norm(geph.pos, 3) < 2.4e7 || norm(geph.pos, 3) > 2.7e7 || fabs(timediff(geph.toe, now_t)) > 3600.0) return -1;
    return store_geph(&geph, 1) ? PRTK_NAVMSG_EPH : 0;
}

int prtk_input_navmsg(int type, int svid, int msgid, int submsgid, const uint8_t *data, int len, int glo_frq)
{
    int ret;
    (void)msgid;
    if (!initialised || now_t.time == 0) return 0;     /* the time comes with the first rover epoch */
    switch (type) {
        case PRTK_NAV_GPS_L1CA: ret = nav_gps(SYS_GPS, svid, submsgid, data, len); break;
        case PRTK_NAV_QZS_L1CA: ret = nav_gps(SYS_QZS, svid, submsgid, data, len); break;
        case PRTK_NAV_GAL_I:    ret = nav_gal(svid, data, len); break;
        case PRTK_NAV_BDS_D1:   ret = nav_bds(svid, data, len); break;
        case PRTK_NAV_GLO_L1CA: ret = nav_glo(svid, data, len, glo_frq); break;
        default: return -1;
    }
    if (ret < 0) navbad++; else navmsgs++;
    return ret;
}
