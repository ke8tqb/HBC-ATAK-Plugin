/*
 * hbc_jni.cpp
 *
 * JNI bridge between Java and the aicodix OFDM modem (short branch).
 * Wraps aicodix encode.cc / decode.cc for in-memory byte[] ↔ short[] PCM
 * instead of file-based WAV I/O.
 *
 * Pulled headers (via CMake FetchContent):
 *   aicodix/dsp  — fft.hh, pcm.hh, wav.hh, mls.hh, blockdc.hh, hilbert.hh …
 *   aicodix/code — polar_encoder.hh, crc.hh, bitman.hh, osd.hh …
 *
 * Local headers (same directory):
 *   encode.cc, decode.cc, psk.hh, polar_tables.hh, schmidl_cox.hh
 */

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <cstring>
#include <algorithm>
#include <cmath>

// aicodix DSP / FEC headers (resolved via CMake include dirs from FetchContent)
#include "complex.hh"
#include "utils.hh"
#include "pcm.hh"

#define LOG_TAG "HBC-OFDM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// =============================================================================
// MemWritePCM — in-memory sink used by the aicodix Encoder
// =============================================================================
class MemWritePCM : public DSP::WritePCM<float> {
    std::vector<int16_t> &buf_;
    int rate_, chan_;
public:
    MemWritePCM(std::vector<int16_t> &buf, int rate, int chan)
        : buf_(buf), rate_(rate), chan_(chan) {}

    // Called by Encoder with stride=2 (real part of complex[], mono output)
    void write(const float *src, int num, int stride = -1) override {
        if (stride < 0) stride = chan_;
        for (int n = 0; n < num; ++n) {
            float v = std::max(-1.0f, std::min(1.0f, src[n * stride]));
            buf_.push_back(static_cast<int16_t>(v * 32767.0f));
        }
    }
    void silence(int num) override {
        for (int i = 0; i < num; ++i)
            buf_.push_back(0);
    }
    bool good()     override { return true; }
    int  rate()     override { return rate_; }
    int  channels() override { return chan_; }
};

// =============================================================================
// MemReadPCM — in-memory source used by the aicodix Decoder
// =============================================================================
class MemReadPCM : public DSP::ReadPCM<float> {
    const int16_t *data_;
    int len_, pos_, rate_, chan_;
public:
    MemReadPCM(const int16_t *data, int len, int rate, int chan)
        : data_(data), len_(len), pos_(0), rate_(rate), chan_(chan) {}

    // Decoder calls: read(reinterpret_cast<float*>(&cmplx_tmp), 1)
    // For mono (channels==1), this sets the real part; Decoder applies Hilbert.
    void read(float *dst, int num, int stride = -1) override {
        if (stride < 0) stride = chan_;
        for (int n = 0; n < num; ++n) {
            for (int c = 0; c < chan_; ++c) {
                dst[n * stride + c] = (pos_ < len_)
                    ? (static_cast<float>(data_[pos_++]) / 32768.0f)
                    : 0.0f;
            }
        }
    }
    void skip(int num) override {
        pos_ = std::min(pos_ + num * chan_, len_);
    }
    bool good()     override { return pos_ < len_; }
    int  rate()     override { return rate_; }
    int  channels() override { return chan_; }
};

// =============================================================================
// Pull aicodix Encoder / Decoder template classes.
// #define main to prevent their standalone entry points from conflicting.
// =============================================================================
#define main __aicodix_encode_main_unused__
#include "encode.cc"
#undef main

#define main __aicodix_decode_main_unused__
#include "decode.cc"
#undef main

// =============================================================================
// JNI: encodeHBC
//   Java:  short[] OFDMModem.encodeHBC(byte[] payload, String callsign, int rate)
//   Input: HBC payload bytes (≤170 bytes)
//   Output: mono 16-bit PCM samples at `sampleRate` Hz
// =============================================================================
extern "C"
JNIEXPORT jshortArray JNICALL
Java_com_atakmap_android_hbc_audio_OFDMModem_encodeHBC(
        JNIEnv *env, jobject /*thiz*/,
        jbyteArray jPayload, jstring jCallsign, jint sampleRate)
{
    // --- 1. Copy payload bytes -----------------------------------------------
    const int DATA_LEN = 170;  // 1360 bits / 8
    uint8_t raw[DATA_LEN]      = {};
    uint8_t scrambled[DATA_LEN] = {};

    jsize payloadLen = env->GetArrayLength(jPayload);
    jbyte *jb = env->GetByteArrayElements(jPayload, nullptr);
    if (jb) {
        memcpy(raw, jb, std::min((int)payloadLen, DATA_LEN));
        env->ReleaseByteArrayElements(jPayload, jb, JNI_ABORT);
    }

    // --- 2. Callsign → base37 integer ----------------------------------------
    const char *csRaw = env->GetStringUTFChars(jCallsign, nullptr);
    long long int call_sign = base37_encoder(csRaw ? csRaw : "ANONYMOUS");
    if (csRaw) env->ReleaseStringUTFChars(jCallsign, csRaw);
    if (call_sign <= 0 || call_sign >= 129961739795077LL)
        call_sign = base37_encoder("ANONYMOUS");

    // --- 3. Scramble (same Xorshift32 applied in encode.cc main()) -----------
    {
        CODE::Xorshift32 sc;
        for (int i = 0; i < DATA_LEN; ++i)
            scrambled[i] = raw[i] ^ sc();
    }

    // --- 4. Detect oper_mode from UNSCRAMBLED data ---------------------------
    int oper_mode = 0;
    for (int i = 128; i < 170; ++i) if (!oper_mode && raw[i]) oper_mode = 14;
    for (int i = 85;  i < 128; ++i) if (!oper_mode && raw[i]) oper_mode = 15;
    for (int i = 0;   i < 85;  ++i) if (!oper_mode && raw[i]) oper_mode = 16;
    if (!oper_mode) oper_mode = 16;  // always transmit something

    // --- 5. Encode to PCM samples --------------------------------------------
    // NOTE: No silence added here. The PTT Delay setting in RadioAudioTransmitter
    // handles pre-signal silence for VOX/PTT keying. Adding silence here would
    // double the lead-in and make every transmission unnecessarily longer.
    //
    // Frame duration at 8000 Hz (fixed regardless of payload size):
    //   pilot (0.18s) + SC sync (0.18s) + meta (0.18s) +
    //   4 data symbols (0.72s) + empty (0.18s) = ~1.26 s of OFDM signal
    std::vector<int16_t> outbuf;
    outbuf.reserve(sampleRate * 4);

    {
        MemWritePCM writer(outbuf, sampleRate, 1 /*mono*/);

        typedef float value;
        typedef DSP::Complex<value> cmplx;
        const int freq_off = 1500;   // standard center frequency for 8000 Hz mono

        switch (sampleRate) {
            case 8000:
                delete new Encoder<value, cmplx, 8000>(
                    &writer, scrambled, freq_off, call_sign, oper_mode);
                break;
            default:
                LOGE("encodeHBC: unsupported sample rate %d", sampleRate);
                return nullptr;
        }
    }

    LOGI("encodeHBC: produced %zu samples (%0.1f s at %d Hz)",
         outbuf.size(), outbuf.size() / (float)sampleRate, sampleRate);

    // --- 6. Return as jshortArray --------------------------------------------
    jshortArray result = env->NewShortArray((jsize)outbuf.size());
    if (result)
        env->SetShortArrayRegion(result, 0, (jsize)outbuf.size(),
                                 reinterpret_cast<const jshort *>(outbuf.data()));
    return result;
}

// =============================================================================
// JNI: decodeFromAudio
//   Java:  byte[] OFDMModem.decodeFromAudio(short[] samples, int rate)
//   Input: mono 16-bit PCM samples captured from AudioRecord
//   Output: HBC payload bytes (unscrambled), or null if no frame found
// =============================================================================
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_atakmap_android_hbc_audio_OFDMModem_decodeFromAudio(
        JNIEnv *env, jobject /*thiz*/,
        jshortArray jSamples, jint sampleRate)
{
    jsize sampLen = env->GetArrayLength(jSamples);
    const jshort *shorts = env->GetShortArrayElements(jSamples, nullptr);
    if (!shorts) return nullptr;

    const int OUT_LEN = 170;
    uint8_t out[OUT_LEN] = {};
    int decoded_len = 0;

    {
        MemReadPCM reader(
            reinterpret_cast<const int16_t *>(shorts),
            (int)sampLen, sampleRate, 1 /*mono*/);

        typedef float value;
        typedef DSP::Complex<value> cmplx;

        switch (sampleRate) {
            case 8000:
                delete new Decoder<value, cmplx, 8000>(out, &decoded_len, &reader, 0);
                break;
            default:
                LOGE("decodeFromAudio: unsupported sample rate %d", sampleRate);
                env->ReleaseShortArrayElements(jSamples,
                    const_cast<jshort *>(shorts), JNI_ABORT);
                return nullptr;
        }
    }

    env->ReleaseShortArrayElements(jSamples,
        const_cast<jshort *>(shorts), JNI_ABORT);

    if (decoded_len <= 0) {
        LOGI("decodeFromAudio: no valid frame in %d samples", (int)sampLen);
        return nullptr;
    }

    // Unscramble: XOR with the same Xorshift32 sequence (XOR is its own inverse)
    {
        CODE::Xorshift32 sc;
        for (int i = 0; i < decoded_len; ++i)
            out[i] ^= sc();
    }

    LOGI("decodeFromAudio: decoded %d bytes", decoded_len);

    jbyteArray result = env->NewByteArray(decoded_len);
    if (result)
        env->SetByteArrayRegion(result, 0, decoded_len,
                                reinterpret_cast<const jbyte *>(out));
    return result;
}
