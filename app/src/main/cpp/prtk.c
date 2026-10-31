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
    initialised = 1;
    return 1;
}

/* Copies a newly decoded ephemeris into the positioning nav (current set). */
static void update_eph(rtcm_t *r)
{
    int prn, sat = r->ephsat, set = r->ephset;
    if (sat <= 0) return;
    if (satsys(sat, &prn) != SYS_GLO) {
        eph_t *in = r->nav.eph + sat - 1 + MAXSAT * set;
        eph_t *cur = nav.eph + sat - 1 + MAXSAT * set;
        eph_t *prev = nav.eph + sat - 1 + MAXSAT * (2 + set);
        if (cur->ttr.time == 0 || (in->iode != cur->iode) || timediff(in->toe, cur->toe) != 0.0) {
            *prev = *cur;
            *cur = *in;
        }
    } else if (prn >= 1 && prn <= NSATGLO) {
        geph_t *in = r->nav.geph + prn - 1;
        geph_t *cur = nav.geph + prn - 1;
        geph_t *prev = nav.geph + prn - 1 + NSATGLO;
        if (cur->tof.time == 0 || in->iode != cur->iode) {
            *prev = *cur;
            *cur = *in;
        }
    }
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
        if (ne < 4) {
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
