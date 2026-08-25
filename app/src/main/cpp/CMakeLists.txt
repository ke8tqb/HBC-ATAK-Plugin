cmake_minimum_required(VERSION 3.18.1)

add_link_options("-Wl,--build-id=none")

project("hbcofdm")

add_library(hbcofdm SHARED hbc-ofdm-jni.cpp)

find_library(log-lib log)

target_link_libraries(hbcofdm ${log-lib})
