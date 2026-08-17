cmake_minimum_required(VERSION 3.22.1)

project(booming_ss_separation LANGUAGES CXX)

add_library(
    booming_ss_separation
    SHARED
    mdx_dsp_jni.cpp
    htdemucs_dsp_jni.cpp
)

target_compile_features(booming_ss_separation PRIVATE cxx_std_17)
target_compile_options(booming_ss_separation PRIVATE -Wall -Wextra -Werror -O3)
target_include_directories(
    booming_ss_separation
    PRIVATE
    third_party/pocketfft
)
target_link_options(
    booming_ss_separation
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_link_libraries(booming_ss_separation PRIVATE dl)
