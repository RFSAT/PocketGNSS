/* JNI bridge for com.rfsat.pocketgnss.rtk.RtkNative (Kotlin object): plain arrays in and out. */
#include <jni.h>
#include <string.h>
#include "prtk.h"

#define FN(name) Java_com_rfsat_pocketgnss_rtk_RtkNative_##name

JNIEXPORT jboolean JNICALL FN(init)(JNIEnv *env, jobject thiz, jint mode, jint navsys, jdouble elmask, jdouble snrmask,
                                    jint armode, jint gloAr, jdouble eratio, jdouble thresar, jdouble maxage, jint minfixsats,
                                    jint ionoFree)
{
    prtk_opt_t o;
    prtk_default_opt(&o);
    o.mode = mode; o.navsys = navsys; o.elmask_deg = elmask; o.snrmask = snrmask;
    o.armode = armode; o.glo_ar = gloAr; o.eratio = eratio; o.thresar = thresar; o.maxage = maxage; o.minfixsats = minfixsats;
    o.iono_free = ionoFree;
    return prtk_init(&o) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jobject thiz) { prtk_free(); }

JNIEXPORT void JNICALL FN(setIono)(JNIEnv *env, jobject thiz, jint ionoFree) { prtk_set_iono(ionoFree); }

JNIEXPORT void JNICALL FN(reset)(JNIEnv *env, jobject thiz) { prtk_reset(); }

JNIEXPORT void JNICALL FN(setTime)(JNIEnv *env, jobject thiz, jint week, jdouble tow) { prtk_set_time(week, tow); }

JNIEXPORT jint JNICALL FN(inputRtcm)(JNIEnv *env, jobject thiz, jbyteArray data, jint off, jint len)
{
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    int r = prtk_input_rtcm((const uint8_t *)b + off, len);
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return r;
}

JNIEXPORT jint JNICALL FN(inputRtcmNav)(JNIEnv *env, jobject thiz, jbyteArray data, jint off, jint len)
{
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    int r = prtk_input_rtcm_nav((const uint8_t *)b + off, len);
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return r;
}

/* out: stat, week, tow, lat, lon, h, x, y, z, sdE, sdN, sdU, vE, vN, vU, ns, ratio, age, baseline, geoid,
 *      floatLat, floatLon, floatH, floatSdE, floatSdN, floatSdU, nfix (27) */
JNIEXPORT jint JNICALL FN(rover)(JNIEnv *env, jobject thiz, jint week, jdouble tow, jint n, jbyteArray sys, jintArray prn,
                                 jbyteArray codes, jdoubleArray P, jdoubleArray L, jfloatArray D, jfloatArray snr,
                                 jintArray lli, jdoubleArray out)
{
    prtk_sol_t s;
    jbyte *cs = (*env)->GetByteArrayElements(env, sys, NULL);
    jint *ip = (*env)->GetIntArrayElements(env, prn, NULL);
    jbyte *cc = (*env)->GetByteArrayElements(env, codes, NULL);
    jdouble *dp = (*env)->GetDoubleArrayElements(env, P, NULL);
    jdouble *dl = (*env)->GetDoubleArrayElements(env, L, NULL);
    jfloat *fd = (*env)->GetFloatArrayElements(env, D, NULL);
    jfloat *fs = (*env)->GetFloatArrayElements(env, snr, NULL);
    jint *il = (*env)->GetIntArrayElements(env, lli, NULL);
    int st = prtk_rover(week, tow, n, (const char *)cs, ip, (const char *)cc, dp, dl, fd, fs, il, &s);
    (*env)->ReleaseByteArrayElements(env, sys, cs, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, prn, ip, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, codes, cc, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, P, dp, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, L, dl, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, D, fd, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, snr, fs, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, lli, il, JNI_ABORT);
    {
        jdouble v[27] = {
            s.stat, s.week, s.tow, s.llh[0], s.llh[1], s.llh[2], s.xyz[0], s.xyz[1], s.xyz[2],
            s.enu_std[0], s.enu_std[1], s.enu_std[2], s.vel_enu[0], s.vel_enu[1], s.vel_enu[2],
            s.ns, s.ratio, s.age, s.baseline, s.geoid,
            s.float_llh[0], s.float_llh[1], s.float_llh[2], s.float_std[0], s.float_std[1], s.float_std[2], s.nfix
        };
        (*env)->SetDoubleArrayRegion(env, out, 0, 27, v);
    }
    return st;
}

/* out: hasPos, x, y, z, lat, lon, h, stationId, nobs, obsWeek, obsTow, neph, ngeph, frames, nssr, ssrWeek, ssrTow,
 *      ncbias (18) */
JNIEXPORT void JNICALL FN(base)(JNIEnv *env, jobject thiz, jdoubleArray out)
{
    prtk_base_t b;
    prtk_base(&b);
    jdouble v[18] = {
        b.has_pos, b.xyz[0], b.xyz[1], b.xyz[2], b.llh[0], b.llh[1], b.llh[2], b.station_id, b.nobs,
        b.obs_week, b.obs_tow, b.neph, b.ngeph, (double)b.frames, b.nssr, b.ssr_week, b.ssr_tow, b.ncbias
    };
    (*env)->SetDoubleArrayRegion(env, out, 0, 18, v);
}

JNIEXPORT jstring JNICALL FN(message)(JNIEnv *env, jobject thiz) { return (*env)->NewStringUTF(env, prtk_message()); }
