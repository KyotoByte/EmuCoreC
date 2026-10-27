#!/usr/bin/env bash
# EmuCoreC: build FFmpeg 8.1.1 for Android (aarch64) with the NDK.
#
# The RPCS3 3rdparty/ffmpeg submodule only carries headers + vcpkg triplets,
# and the RPCS3-Android/ffmpeg-android prebuilts are stuck at 5.1, which is too
# old for the FFmpeg 6+ APIs RPCS3 master uses. So we build the matching
# upstream release from source. Output lands in app/build/ffmpeg-android and is
# consumed by android/ffmpeg.cmake via 3rdparty_ffmpeg.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=8.1.1
API=29
JOBS="${FFMPEG_JOBS:-8}"
SRC_DIR="${FFMPEG_SRC_DIR:-$ROOT/app/build/ffmpeg-src}"
OUT_DIR="${FFMPEG_OUT_DIR:-$ROOT/app/build/ffmpeg-android}"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    # Windows (Git Bash): NDK under %LOCALAPPDATA%, MinGW binutils and make.
    LOCAL="${LOCALAPPDATA//\\//}"
    NDK="${NDK_DIR:-$LOCAL/Android/Sdk/ndk/29.0.14206865}"
    MAKE="${FFMPEG_MAKE:-$LOCAL/mingw/mingw64/bin/mingw32-make.exe}"
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
    AR="$LOCAL/mingw/mingw64/bin/ar"
    RANLIB="$LOCAL/mingw/mingw64/bin/ranlib"
    NM="$LOCAL/mingw/mingw64/bin/nm"
    HOST_CC="$LOCAL/mingw/mingw64/bin/gcc"
    ;;
  *)
    # Linux / macOS: NDK from ANDROID_HOME, the NDK's own llvm binutils.
    : "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    NDK="${NDK_DIR:-$ANDROID_HOME/ndk/29.0.14206865}"
    MAKE="${FFMPEG_MAKE:-make}"
    case "$(uname -s)" in Darwin*) HOST_TAG=darwin-x86_64 ;; *) HOST_TAG=linux-x86_64 ;; esac
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
    AR="$TOOLCHAIN/bin/llvm-ar"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    NM="$TOOLCHAIN/bin/llvm-nm"
    HOST_CC="${HOST_CC:-cc}"
    ;;
esac
CC="$TOOLCHAIN/bin/clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"

# Download and unpack the source once.
if [ ! -f "$SRC_DIR/FFmpeg-n$VERSION/configure" ]; then
  mkdir -p "$SRC_DIR"
  if [ ! -f "$SRC_DIR/ffmpeg-$VERSION.tar.gz" ]; then
    curl -L --fail -o "$SRC_DIR/ffmpeg-$VERSION.tar.gz" \
      "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$VERSION.tar.gz"
  fi
  tar -xzf "$SRC_DIR/ffmpeg-$VERSION.tar.gz" -C "$SRC_DIR"
fi

cd "$SRC_DIR/FFmpeg-n$VERSION"

mkdir -p "$OUT_DIR"

./configure \
  --prefix="$OUT_DIR" \
  --cc="$CC" \
  --host-cc="$HOST_CC" \
  --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP" \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  `# -fvisibility=hidden: the NEON asm addresses FFmpeg's tables PC-relative, which` \
  `# only links into libemucorec-core.so if those symbols are not exported.` \
  --extra-cflags="--target=aarch64-linux-android$API --sysroot=$SYSROOT -O3 -fPIC -fvisibility=hidden" \
  --extra-ldflags="--target=aarch64-linux-android$API --sysroot=$SYSROOT" \
  --enable-static --disable-shared --enable-pic \
  `# KyotoByte: FFmpeg's hand-written AArch64 NEON code ON (it was --disable-asm,` \
  `# i.e. plain C for every decoder: PS3 videos/cutscenes (MPEG-2/H.264/ATRAC),` \
  `# scaling and resampling). Set FFMPEG_ASM=0 for the old plain-C build.` \
  $( [ "${FFMPEG_ASM:-1}" = "1" ] && echo "--enable-asm --enable-neon --enable-inline-asm" || echo "--disable-asm" ) \
  --disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-avfilter \
  --disable-network --disable-autodetect

# mingw32-make defaults to cmd.exe as its shell, which breaks ffmpeg's
# response-file recipe (echo $^ > file). Force a POSIX shell.
"$MAKE" SHELL="$(command -v sh)" -j"$JOBS" || true
"$MAKE" SHELL="$(command -v sh)" install || true

# The ar @response-file step is unreliable under Windows make (the .objs list
# file is frequently missing when ar runs); re-archive from the object globs.
cd "$SRC_DIR/FFmpeg-n$VERSION"
for lib in libavcodec libavformat libavutil libswscale libswresample; do
  if [ ! -s "$lib/$lib.a" ] || [ "$(stat -c%s "$lib/$lib.a")" -lt 1000 ]; then
    echo "EmuCoreC: re-archiving $lib from object glob"
    "$AR" rcs "$lib/$lib.a" $(find "$lib" -name "*.o" ! -path "*tests*")
  fi
done

mkdir -p "$OUT_DIR/lib"
cp libavcodec/libavcodec.a libavformat/libavformat.a \
   libavutil/libavutil.a libswscale/libswscale.a libswresample/libswresample.a \
   "$OUT_DIR/lib/"

echo "EmuCoreC: FFmpeg $VERSION built into $OUT_DIR"
