/*
 * JNI bridge for the aicodix rattlegram-short OFDM modem (COFDMTV).
 * Adapted from rattlegram's native-lib.cpp (Ahmet Inan, aicodix).
 */

#include <jni.h>
#define assert(expr) do {} while (0)
#include "encoder.hh"
#include "decoder.hh"

static EncoderInterface *encoder;
static DecoderInterface *decoder;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_hbc_OfdmNative_createEncoder(
        JNIEnv *, jclass, jint sampleRate) {
    if (encoder && encoder->rate() == sampleRate)
        return true;
    delete encoder;
    switch (sampleRate) {
        case 8000:  encoder = new(std::nothrow) Encoder<8000>();  break;
        case 16000: encoder = new(std::nothrow) Encoder<16000>(); break;
        case 32000: encoder = new(std::nothrow) Encoder<32000>(); break;
        case 44100: encoder = new(std::nothrow) Encoder<44100>(); break;
        case 48000: encoder = new(std::nothrow) Encoder<48000>(); break;
        default:    encoder = nullptr;
    }
    return encoder != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_atakmap_android_hbc_OfdmNative_destroyEncoder(JNIEnv *, jclass) {
    delete encoder;
    encoder = nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_atakmap_android_hbc_OfdmNative_configureEncoder(
        JNIEnv *env, jclass,
        jbyteArray JNI_payload, jbyteArray JNI_callSign,
        jint carrierFrequency, jint noiseSymbols, jboolean fancyHeader) {
    if (!encoder)
        return;
    jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
    if (!payload)
        return;
    jbyte *callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
    if (callSign) {
        encoder->configure(
                reinterpret_cast<uint8_t *>(payload),
                reinterpret_cast<int8_t *>(callSign),
                carrierFrequency, noiseSymbols, fancyHeader);
        env->ReleaseByteArrayElements(JNI_callSign, callSign, JNI_ABORT);
    }
    env->ReleaseByteArrayElements(JNI_payload, payload, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_hbc_OfdmNative_produceEncoder(
        JNIEnv *env, jclass, jshortArray JNI_audioBuffer, jint channelSelect) {
    if (!encoder)
        return false;
    jshort *audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
    jboolean okay = false;
    if (audioBuffer)
        okay = encoder->produce(audioBuffer, channelSelect);
    env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, 0);
    return okay;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_hbc_OfdmNative_createDecoder(
        JNIEnv *, jclass, jint sampleRate) {
    if (decoder && decoder->rate() == sampleRate)
        return true;
    delete decoder;
    switch (sampleRate) {
        case 8000:  decoder = new(std::nothrow) Decoder<8000>();  break;
        case 16000: decoder = new(std::nothrow) Decoder<16000>(); break;
        case 32000: decoder = new(std::nothrow) Decoder<32000>(); break;
        case 44100: decoder = new(std::nothrow) Decoder<44100>(); break;
        case 48000: decoder = new(std::nothrow) Decoder<48000>(); break;
        default:    decoder = nullptr;
    }
    return decoder != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_atakmap_android_hbc_OfdmNative_destroyDecoder(JNIEnv *, jclass) {
    delete decoder;
    decoder = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_atakmap_android_hbc_OfdmNative_feedDecoder(
        JNIEnv *env, jclass,
        jshortArray JNI_audioBuffer, jint sampleCount, jint channelSelect) {
    if (!decoder)
        return false;
    jshort *audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
    if (!audioBuffer)
        return false;
    jboolean status = decoder->feed(
            reinterpret_cast<int16_t *>(audioBuffer), sampleCount, channelSelect);
    env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, JNI_ABORT);
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_OfdmNative_processDecoder(JNIEnv *, jclass) {
    if (!decoder)
        return STATUS_HEAP;
    return decoder->process();
}

extern "C" JNIEXPORT void JNICALL
Java_com_atakmap_android_hbc_OfdmNative_stagedDecoder(
        JNIEnv *env, jclass,
        jfloatArray JNI_cfo, jintArray JNI_mode, jbyteArray JNI_callSign) {
    if (!decoder)
        return;
    jfloat *cfo = env->GetFloatArrayElements(JNI_cfo, nullptr);
    jint *mode = env->GetIntArrayElements(JNI_mode, nullptr);
    jbyte *callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
    if (cfo && mode && callSign)
        decoder->staged(
                reinterpret_cast<float *>(cfo),
                reinterpret_cast<int32_t *>(mode),
                reinterpret_cast<uint8_t *>(callSign));
    if (callSign) env->ReleaseByteArrayElements(JNI_callSign, callSign, 0);
    if (mode) env->ReleaseIntArrayElements(JNI_mode, mode, 0);
    if (cfo) env->ReleaseFloatArrayElements(JNI_cfo, cfo, 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_atakmap_android_hbc_OfdmNative_fetchDecoder(
        JNIEnv *env, jclass, jbyteArray JNI_payload) {
    jint status = -1;
    if (decoder) {
        jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
        if (payload)
            status = decoder->fetch(reinterpret_cast<uint8_t *>(payload));
        env->ReleaseByteArrayElements(JNI_payload, payload, 0);
    }
    return status;
}
