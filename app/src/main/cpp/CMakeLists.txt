cmake_minimum_required(VERSION 3.22.1)

project(emucorec-jni LANGUAGES C CXX)

# Google Play requires 16 KiB page size support: keep every ELF load segment aligned
# even when a toolchain default changes.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The JNI GLUE, not the emulator core. Produces libemucorec-jni.so, which
# System.loadLibrary("emucorec-jni") brings up; it then dlopen()s the core
# (libemucorec-core.so, shipped as a prebuilt in jniLibs) and forwards to its
# emucorec_* entry points. The JNI methods bind to com.sbro.emucorec.core.Ps3Core.
add_library(emucorec-jni SHARED
    emucorec-jni.cpp
)

target_link_libraries(emucorec-jni
    android
    log
)
