#!/usr/bin/env bash
# EmuCoreC Android arm64 configure.
#
# Configures from core/, the emulator core project root, not from android/ --
# upstream RPCS3 assumes CMAKE_SOURCE_DIR is its tree root (FindWolfSSL.cmake,
# FindZLIB.cmake, 3rdparty/protobuf, 3rdparty/llvm all build paths off it).
# The android/ glue at the repository root is added from core/CMakeLists.txt.
#
# Usage:  android/configure.sh [extra cmake args...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  case "$(uname -s)" in
    Darwin*) ANDROID_HOME="$HOME/Library/Android/sdk" ;;
    *)       ANDROID_HOME="$HOME/Android/Sdk" ;;
  esac
fi
# NDK 29 (clang 21), NOT NDK 28.2 (clang 19.0.1).
# Upstream's stated floor is clang-19 and 28.2 sits exactly on it, but clang
# 19.0.1 mis-analyses fmt::throw_exception -- that is a CTAD struct whose
# constructor AND destructor are [[noreturn]], not a function -- so every switch
# default: that ends in it trips -Werror,-Wreturn-type. It killed 4 TUs in
# rpcs3_emu (SPUThread.{h,cpp}, SPULLVMRecompiler.cpp, SPUCommonRecompiler.cpp,
# lv2.cpp) at 2510/3123. Verified: all 4 compile with 0 errors under clang 21.
# Do NOT "fix" this with -Wno-error=return-type; the code is fine, the old
# compiler was not. rpcsx-ui-android pins the NDK 29 line for the same reason.
: "${NDK_VERSION:=29.0.14206865}"
: "${CMAKE_VERSION:=3.30.5}"
: "${ANDROID_API:=29}"          # keep in step with app minSdk (29) and configure-core.cmd
: "${BUILD_DIR:=$ROOT/build-android}"

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
CM="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"

[ -d "$NDK" ] || { echo "NDK not found: $NDK" >&2; exit 1; }
[ -x "$CM/cmake" ] || { echo "cmake not found: $CM/cmake" >&2; exit 1; }

exec "$CM/cmake" -S "$ROOT/core" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  `# wrong-for-cross-compile upstream defaults` \
  -DUSE_NATIVE_INSTRUCTIONS=OFF \
  -DUSE_SDL=OFF \
  -DUSE_SYSTEM_SDL=OFF \
  -DUSE_GAMEMODE=OFF \
  `# no system libs inside the NDK sysroot` \
  -DUSE_SYSTEM_LIBUSB=OFF \
  -DUSE_SYSTEM_CURL=OFF \
  -DUSE_SYSTEM_OPENCV=OFF \
  -DUSE_SYSTEM_FFMPEG=OFF \
  -DUSE_SYSTEM_ZLIB=ON \
  `# desktop-only features` \
  -DUSE_DISCORD_RPC=OFF \
  -DUSE_FAUDIO=OFF \
  -DUSE_LIBEVDEV=OFF \
  `# LLVM 22 from the pinned submodule, statically linked` \
  -DWITH_LLVM=ON \
  -DBUILD_LLVM=ON \
  -DSTATIC_LINK_LLVM=ON \
  `# KyotoByte: LTO back ON (upstream RPCS3's default; CMake IPO = ThinLTO with` \
  `# clang) for rpcs3_emu + emucorec-core. EMUCOREC_LTO=OFF restores the old setup.` \
  -DUSE_LTO="${EMUCOREC_LTO:-ON}" \
  `# KyotoByte: no debug info in Release objects. The .so is stripped anyway, so the` \
  `# shipped binary is identical; the build tree shrinks by ~1 GB (NDK adds -g).` \
  "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0" \
  "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -g0" \
  -DASMJIT_NO_SHM_OPEN=ON \
  `# same LLVM cross settings as configure-core.cmd (official Windows build)` \
  -DLLVM_ENABLE_LIBCXX=ON \
  -DLLVM_HOST_TRIPLE=aarch64-linux-android \
  -DPython3_EXECUTABLE="$(command -v python3)" \
  "-DCROSS_TOOLCHAIN_FLAGS_NATIVE=-DCMAKE_C_COMPILER=${HOST_CC:-cc};-DCMAKE_CXX_COMPILER=${HOST_CXX:-c++};-DCMAKE_ASM_COMPILER=${HOST_CC:-cc}" \
  "$@"
