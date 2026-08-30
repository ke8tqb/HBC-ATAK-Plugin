cmake_minimum_required(VERSION 3.18.1)

add_link_options("-Wl,--build-id=none")

project("hbcofdm")

add_library(hbcofdm SHARED hbc-ofdm-jni.cpp)

find_library(log-lib log)

target_link_libraries(hbcofdm ${log-lib})

# --- Mercury HF modem (FreeDV DATAC raw data modes, GPLv3) ---------------
# Vendored from Rhizomatica's Mercury tree (mercury/modem/freedv), which in
# turn vendors the codec2/FreeDV modem. The source list mirrors Mercury's
# own libfreedvdata.a OBJS_COMMON.
file(GLOB MERCURY_FREEDV_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/mercury/*.c")

add_library(hbcmercury SHARED hbc-mercury-jni.cpp hbc-mercury-compat.c ${MERCURY_FREEDV_SOURCES})

target_include_directories(hbcmercury PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/mercury")

# freedv_api.c embeds the codec2 git hash; Mercury's Makefile passes it in.
target_compile_definitions(hbcmercury PRIVATE GIT_HASH="vendored")

# FreeDV is plain C99 and needs exceptions/RTTI-free C++ only for the JNI
# file; relax the project-wide C++ flags' -fno-exceptions is fine, but the
# C sources must not get C++-only flags. CMake separates C/CXX flags, so
# just set the C options here.
target_compile_options(hbcmercury PRIVATE
        $<$<COMPILE_LANGUAGE:C>:-O3 -ffast-math -Wno-unused-parameter -Wno-unused-variable -Wno-strict-overflow -Wno-implicit-function-declaration>)

target_link_libraries(hbcmercury ${log-lib} m)
