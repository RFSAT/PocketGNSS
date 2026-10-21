/*
 * PocketGNSS phone RTK engine over RTKLIB demo5. See prtk.h.
 *
 * Data flow, as in RTKLIB's rtksvr but without its threads and streams:
 *   caster RTCM 3 -> input_rtcm3 -> base epoch (obs rcv=2), station position (rtk.rb), ephemeris (nav)
 *   phone epoch   -> obsd_t (rcv=1)  -> rtkpos(rover + latest base epoch, nav) -> solution
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
static char message[256] = "";
static double maxage = 30.0;

void prtk_default_opt(prtk_opt_t *o)
{
    o->mode = PRTK_MODE_KINEMATIC;
    o->navsys = PRTK_SYS_GPS | PRTK_SYS_GAL | PRTK_SYS_GLO | PRTK_SYS_CMP | PRTK_SYS_QZS;
    o->elmask_deg = 15.0;
    o->snrmask = 0.0;
    o->armode = ARMODE_CONT;
    o->glo_ar = 0;
    o->eratio = 300.0;   /* phone code noise is much larger than geodetic receivers' */
    o->thresar = 3.0;
    o->maxage = 30.0;
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
        default:               opt.mode = PMODE_KINEMA; break;
    }
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
    opt.minfixsats = 4;
    opt.minholdsats = 5;
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

int prtk_rover(int week, double tow, int n, const char *sys, const int *prn, const char *codes,
               const double *P, const double *L, const float *D, const float *snr, const int *lli,
               prtk_sol_t *sol)
{
    static obsd_t obs[MAXOBS * 2];
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
    qsort(rov, nr, sizeof(obsd_t), cmp_obs);
    for (i = 0; i < nr; i++) obs[nobs++] = rov[i];
    /* the latest base epoch, if recent enough */
    if (nbase > 0 && fabs(timediff(t, base[0].time)) <= maxage && norm(rtk.rb, 3) > 0.0) {
        for (i = 0; i < nbase; i++) obs[nobs++] = base[i];
    }

    rtk.neb = 0;
    rtkpos(&rtk, obs, nobs, &nav);
    if (rtk.neb > 0) {
        k = rtk.neb < (int)sizeof(message) - 1 ? rtk.neb : (int)sizeof(message) - 1;
        memcpy(message, rtk.errbuf, k); message[k] = '\0';
    } else message[0] = '\0';

    sol->stat = rtk.sol.stat;
    if (rtk.sol.stat == SOLQ_NONE) return PRTK_NONE;
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
    sol->geoid = geoidh(pos);
    sol->ns = rtk.sol.ns;
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
}

const char *prtk_message(void) { return message; }
