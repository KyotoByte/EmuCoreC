#!/usr/bin/env bash
# EmuCoreC (KyotoByte Edition): build libemucorec-core.so on Linux/macOS
# without Gradle: FFmpeg -> CMake configure -> Ninja -> strip -> stage in jniLibs.
#
# Usage: android/build-core.sh [extra cmake -D args...]
#   ANDROID_HOME   Android SDK (needs ndk;29.0.14206865 and cmake;3.30.5)
#   BUILD_DIR      build tree (default app/build/emucorec-core-build, same as Gradle)
#   JOBS           parallel jobs (default: nproc)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
: "${NDK_VERSION:=29.0.14206865}"
: "${CMAKE_VERSION:=3.30.5}"
: "${BUILD_DIR:=$ROOT/app/build/emucorec-core-build}"
: "${JOBS:=$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
export ANDROID_HOME NDK_VERSION CMAKE_VERSION BUILD_DIR
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CM="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"
case "$(uname -s)" in Darwin*) HOST_TAG=darwin-x86_64 ;; *) HOST_TAG=linux-x86_64 ;; esac

if [ ! -f "$ROOT/app/build/ffmpeg-android/lib/libavcodec.a" ]; then
  NDK_DIR="$NDK" FFMPEG_JOBS="$JOBS" bash "$ROOT/android/build-ffmpeg.sh"
fi
if [ ! -f "$BUILD_DIR/build.ninja" ]; then
  bash "$ROOT/android/configure.sh" "$@"
fi
"$CM/ninja" -C "$BUILD_DIR" -j"$JOBS" emucorec-core
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
"$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip" \
  -o "$ROOT/app/src/main/jniLibs/arm64-v8a/libemucorec-core.so" \
  "$BUILD_DIR/android/libemucorec-core.so"
echo "EmuCoreC: staged app/src/main/jniLibs/arm64-v8a/libemucorec-core.so"
