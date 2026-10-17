# EmuCoreC: FFmpeg 8.x for Android, built from source by android/build-ffmpeg.sh.
#
# Upstream 3rdparty/CMakeLists.txt wraps its whole FFMPEG block in
# `if(NOT ANDROID)` -- it deliberately does not define 3rdparty_ffmpeg on
# Android and expects the Android build to supply it. But line ~378 then does
# `add_library(3rdparty::ffmpeg ALIAS 3rdparty_ffmpeg)` UNGUARDED, so the target
# must already exist by the time 3rdparty/ is processed. Hence this file is
# include()d from core/CMakeLists.txt *before* add_subdirectory(3rdparty).
#
# The RPCS3 submodule only carries headers/vcpkg triplets and the
# RPCS3-Android/ffmpeg-android prebuilts are stuck at 5.1, which is too old for
# the FFmpeg 6+ APIs RPCS3 master uses (avcodec_get_supported_config etc.), so
# the matching 8.1.1 release is built from source for aarch64 Android.
#
# CMAKE_SOURCE_DIR is core/; the prebuilt FFmpeg and this glue live at the
# repository root, one level up.

set(FFMPEG_ANDROID_ROOT "${CMAKE_SOURCE_DIR}/../app/build/ffmpeg-android")

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64")
    set(FFMPEG_ANDROID_ARCH "arm64-v8a")
else()
    set(FFMPEG_ANDROID_ARCH "x86-64")
endif()

if(NOT EXISTS "${FFMPEG_ANDROID_ROOT}/lib/libavcodec.a")
    message(FATAL_ERROR "EmuCoreC: Android FFmpeg prebuilts not found at ${FFMPEG_ANDROID_ROOT}. Run the :app:buildFfmpeg Gradle task (or android/build-ffmpeg.sh) first.")
endif()

add_library(ffmpeg_avcodec STATIC IMPORTED)
set_target_properties(ffmpeg_avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_ANDROID_ROOT}/lib/libavcodec.a")
add_library(ffmpeg_avformat STATIC IMPORTED)
set_target_properties(ffmpeg_avformat PROPERTIES IMPORTED_LOCATION "${FFMPEG_ANDROID_ROOT}/lib/libavformat.a")
add_library(ffmpeg_avutil STATIC IMPORTED)
set_target_properties(ffmpeg_avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_ANDROID_ROOT}/lib/libavutil.a")
add_library(ffmpeg_swscale STATIC IMPORTED)
set_target_properties(ffmpeg_swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_ANDROID_ROOT}/lib/libswscale.a")
add_library(ffmpeg_swresample STATIC IMPORTED)
set_target_properties(ffmpeg_swresample PROPERTIES IMPORTED_LOCATION "${FFMPEG_ANDROID_ROOT}/lib/libswresample.a")

add_library(3rdparty_ffmpeg INTERFACE)
target_include_directories(3rdparty_ffmpeg SYSTEM INTERFACE "${CMAKE_SOURCE_DIR}/3rdparty/ffmpeg/include")
target_link_libraries(3rdparty_ffmpeg INTERFACE
    ffmpeg_avcodec
    ffmpeg_avformat
    ffmpeg_avutil
    ffmpeg_swscale
    ffmpeg_swresample
    android
    log
)
