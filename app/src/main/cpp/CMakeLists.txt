cmake_minimum_required(VERSION 3.22.1)
project(pocketgnss_rtk C)

# Phone RTK engine: RTKLIB demo5 subset (rtklib/) + the PocketGNSS wrapper (prtk.c) + JNI bridge.
set(RTKLIB_SRC
    rtklib/rtkcmn.c rtklib/rtkpos.c rtklib/lambda.c rtklib/ephemeris.c rtklib/preceph.c rtklib/sbas.c
    rtklib/ionex.c rtklib/pntpos.c rtklib/ppp.c rtklib/ppp_ar.c rtklib/rtcm.c rtklib/rtcm2.c rtklib/rtcm3.c
    rtklib/rtcm3e.c rtklib/tides.c rtklib/geoid.c rtklib/trace.c)

add_library(prtk SHARED prtk.c prtk_jni.c ${RTKLIB_SRC})
target_include_directories(prtk PRIVATE rtklib .)
# Systems as in RTKLIB's console apps; three frequency slots (L1, L2, L5) so phones' L1 + L5 are used.
target_compile_definitions(prtk PRIVATE ENAGLO ENAGAL ENAQZS ENACMP ENAIRN NFREQ=3 NEXOBS=0)
target_compile_options(prtk PRIVATE -O2 -std=gnu99 -w -fvisibility=hidden)
# 16 KB page alignment (required for Android 15+ devices on Google Play).
target_link_options(prtk PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
target_link_libraries(prtk m log)
