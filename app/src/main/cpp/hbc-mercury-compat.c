/*
 * hbc-mercury-compat.c — libm compatibility shims for the vendored
 * FreeDV/codec2 sources (app/src/main/cpp/mercury).
 *
 * Android's bionic libm only gained the C99 complex functions at
 * API 23+, and the plugin's minSdk targets an older libm where
 * cargf() is not exported. FreeDV's ofdm.c uses cargf(); provide a
 * trivial implementation so libhbcmercury.so links everywhere.
 */
#include <complex.h>
#include <math.h>

float cargf(float _Complex z) {
    return atan2f(cimagf(z), crealf(z));
}

float cabsf(float _Complex z) {
    return hypotf(crealf(z), cimagf(z));
}
