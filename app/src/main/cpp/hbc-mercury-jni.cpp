/*
 * JNI bridge for the Mercury HF modem physical layer (FreeDV DATAC raw
 * data modes, GPLv3, vendored from Rhizomatica's Mercury tree at
 * https://github.com/Rhizomatica/mercury which in turn vendors the
 * codec2/FreeDV modem by David Rowe et al.).
 *
 * The burst layout mirrors Mercury's modem.c send_modulated_data():
 *   head silence -> preamble -> N x (payload + CRC16) frames -> postamble
 * and the receive path mirrors receive_modulated_data():
 *   freedv_nin() sample chunks -> freedv_rawdatarx() -> CRC-valid bytes.
 *
 * All FreeDV DATAC modes operate at 8000 Hz, 16-bit mono.
 */

#include <jni.h>
#include <cstring>
#include <new>

extern "C" {
#include "freedv_api.h"
}

static struct freedv *txModem;
static struct freedv *rxModem;

static struct freedv *openMode(jint mode) {
    int m;
    switch (mode) {
        case 0:  m = FREEDV_MODE_DATAC4;  break;  // ~87 bit/s, very robust (-4 dB SNR)
        case 1:  m = FREEDV_MODE_DATAC3;  break;  // ~321 bit/s, robust (0 dB SNR)
        case 2:  m = FREEDV_MODE_DATAC1;  break;  // ~980 bit/s, moderate (5 dB SNR)
        default: return nullptr;
    }
    struct freedv *f = freedv_open(m);
    if (f)
        freedv_set_frames_per_burst(f, 1);
    return f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_hbc_MercuryNative_create(
        JNIEnv *, jclass, jint mode) {
    if (txModem) { freedv_close(txModem); txModem = nullptr; }
    if (rxModem) { freedv_close(rxModem); rxModem = nullptr; }
    txModem = openMode(mode);
    rxModem = openMode(mode);
    if (!txModem || !rxModem) {
        if (txModem) { freedv_close(txModem); txModem = nullptr; }
        if (rxModem) { freedv_close(rxModem); rxModem = nullptr; }
        return false;
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_atakmap_android_hbc_MercuryNative_destroy(JNIEnv *, jclass) {
    if (txModem) { freedv_close(txModem); txModem = nullptr; }
    if (rxModem) { freedv_close(rxModem); rxModem = nullptr; }
}

/** Usable payload bytes per modem frame (frame size minus the 16-bit CRC). */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_payloadBytesPerFrame(JNIEnv *, jclass) {
    if (!txModem)
        return 0;
    return freedv_get_bits_per_modem_frame(txModem) / 8 - 2;
}

/**
 * Render one complete TX burst for a single frame payload (must be exactly
 * payloadBytesPerFrame() bytes; caller pads). Layout matches Mercury:
 * preamble + frame(+CRC16) + postamble. Returns the PCM sample count
 * written into pcmOut, or -1 on error. pcmOut must be sized via
 * maxBurstSamples().
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_txBurst(
        JNIEnv *env, jclass, jbyteArray JNI_frame, jshortArray JNI_pcmOut) {
    if (!txModem)
        return -1;
    size_t bytesPerFrame = (size_t) freedv_get_bits_per_modem_frame(txModem) / 8;
    size_t payloadBytes = bytesPerFrame - 2;
    if ((size_t) env->GetArrayLength(JNI_frame) != payloadBytes)
        return -1;

    int nModOut = freedv_get_n_tx_modem_samples(txModem);
    int maxPre = nModOut * 2;   // conservative, same as Mercury's estimate
    jint outCap = env->GetArrayLength(JNI_pcmOut);
    if (outCap < maxPre * 2 + nModOut)
        return -1;

    jbyte *payload = env->GetByteArrayElements(JNI_frame, nullptr);
    jshort *pcm = env->GetShortArrayElements(JNI_pcmOut, nullptr);
    if (!payload || !pcm) {
        if (payload) env->ReleaseByteArrayElements(JNI_frame, payload, JNI_ABORT);
        if (pcm) env->ReleaseShortArrayElements(JNI_pcmOut, pcm, JNI_ABORT);
        return -1;
    }

    unsigned char *frame = new(std::nothrow) unsigned char[bytesPerFrame];
    if (!frame) {
        env->ReleaseByteArrayElements(JNI_frame, payload, JNI_ABORT);
        env->ReleaseShortArrayElements(JNI_pcmOut, pcm, JNI_ABORT);
        return -1;
    }
    memcpy(frame, payload, payloadBytes);
    unsigned short crc = freedv_gen_crc16(frame, (int) payloadBytes);
    frame[bytesPerFrame - 2] = (unsigned char) (crc >> 8);
    frame[bytesPerFrame - 1] = (unsigned char) (crc & 0xFF);

    jint n = 0;
    n += freedv_rawdatapreambletx(txModem, (short *) pcm + n);
    freedv_rawdatatx(txModem, (short *) pcm + n, frame);
    n += nModOut;
    n += freedv_rawdatapostambletx(txModem, (short *) pcm + n);

    delete[] frame;
    env->ReleaseByteArrayElements(JNI_frame, payload, JNI_ABORT);
    env->ReleaseShortArrayElements(JNI_pcmOut, pcm, 0);
    return n;
}

/** Worst-case burst size in samples, for sizing the txBurst() buffer. */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_maxBurstSamples(JNIEnv *, jclass) {
    if (!txModem)
        return 0;
    int nModOut = freedv_get_n_tx_modem_samples(txModem);
    return nModOut * 2 * 2 + nModOut;   // preamble + postamble (2x each, est.) + frame
}

/** Number of PCM samples the demodulator wants next (freedv_nin). */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_rxNin(JNIEnv *, jclass) {
    if (!rxModem)
        return 0;
    return freedv_nin(rxModem);
}

/** Upper bound for the rxProcess() sample buffer (freedv_get_n_max_modem_samples). */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_rxMaxSamples(JNIEnv *, jclass) {
    if (!rxModem)
        return 0;
    return freedv_get_n_max_modem_samples(rxModem);
}

/**
 * Feed exactly rxNin() samples; returns the number of CRC-valid bytes
 * written into frameOut (payload + 2 CRC bytes), or 0 when no frame
 * completed. frameOut must hold bits_per_modem_frame/8 bytes.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_rxProcess(
        JNIEnv *env, jclass, jshortArray JNI_pcm, jbyteArray JNI_frameOut) {
    if (!rxModem)
        return 0;
    size_t bytesPerFrame = (size_t) freedv_get_bits_per_modem_frame(rxModem) / 8;
    if ((size_t) env->GetArrayLength(JNI_frameOut) < bytesPerFrame)
        return 0;
    jshort *pcm = env->GetShortArrayElements(JNI_pcm, nullptr);
    jbyte *out = env->GetByteArrayElements(JNI_frameOut, nullptr);
    jint n = 0;
    if (pcm && out)
        n = (jint) freedv_rawdatarx(rxModem, (unsigned char *) out, (short *) pcm);
    if (out) env->ReleaseByteArrayElements(JNI_frameOut, out, 0);
    if (pcm) env->ReleaseShortArrayElements(JNI_pcm, pcm, JNI_ABORT);
    return n;
}

/** Demodulator sync state (0 = searching, non-zero = in sync). */
extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_MercuryNative_rxSync(JNIEnv *, jclass) {
    if (!rxModem)
        return 0;
    return freedv_get_sync(rxModem);
}
