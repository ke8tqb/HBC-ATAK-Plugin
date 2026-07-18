cmake_minimum_required(VERSION 3.22)
project(hbc_ofdm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─────────────────────────────────────────────────────────────────────────────
# FetchContent: pull the aicodix DSP and FEC header-only libraries.
# These provide the signal-processing, polar-code, FFT, MLS, CRC, etc.
# headers that encode.cc and decode.cc depend on.
#
# REQUIRES INTERNET ON FIRST SYNC. Results are cached in the CMake build
# directory under .cxx/ — subsequent builds work offline.
# ─────────────────────────────────────────────────────────────────────────────
include(FetchContent)

FetchContent_Declare(aicodix_dsp
    GIT_REPOSITORY https://github.com/aicodix/dsp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
    SOURCE_DIR     ${CMAKE_CURRENT_SOURCE_DIR}/deps/dsp
)
FetchContent_Declare(aicodix_code
    GIT_REPOSITORY https://github.com/aicodix/code.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
    SOURCE_DIR     ${CMAKE_CURRENT_SOURCE_DIR}/deps/code
)
FetchContent_MakeAvailable(aicodix_dsp aicodix_code)

# ─────────────────────────────────────────────────────────────────────────────
# Shared library (.so) embedded in the APK.
# Only hbc_jni.cpp is compiled; it #include-s encode.cc and decode.cc
# from the same directory, with their main() functions macro-renamed.
# ─────────────────────────────────────────────────────────────────────────────
add_library(hbc-ofdm SHARED
    hbc_jni.cpp
)

target_include_directories(hbc-ofdm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}        # encode.cc, decode.cc, psk.hh, polar_tables.hh, schmidl_cox.hh
    ${aicodix_dsp_SOURCE_DIR}          # fft.hh, pcm.hh, wav.hh, mls.hh, blockdc.hh, hilbert.hh ...
    ${aicodix_code_SOURCE_DIR}         # polar_encoder.hh, crc.hh, bitman.hh, osd.hh, bch_enc.hh ...
)

target_link_libraries(hbc-ofdm
    android
    log
)
