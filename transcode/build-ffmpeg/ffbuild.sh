#!/usr/bin/env bash
# Cross-compile a 16KB-aligned, full-GPL ffmpeg executable for Android — arm64-v8a (device) and
# x86_64 (emulator). Each ABI yields a static-PIE `libffmpeg.so` (libx264 + libx265 + MediaCodec),
# verified for the x265 libc++ pkg-config fix and 16KB LOAD alignment, then copied under jniLibs/.
#
# Usage:  bash ffbuild.sh [arm64-v8a|x86_64|all]   (default: all)
# Output: <repo>/transcode/src/main/jniLibs/<abi>/libffmpeg.so
set -euo pipefail

NDK=/opt/android-sdk/ndk/27.3.13750724
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26
ROOT=/opt/ff
SRC=$ROOT/src
JNILIBS=${JNILIBS_DIR:-/mnt/c/Users/Public/sieve-android/transcode/src/main/jniLibs}
J=$(nproc)
mkdir -p "$SRC"

fatal() { echo "FATAL: $*" >&2; exit 1; }

build_abi() {
  local ABI=$1 TARGET=$2 ARCH=$3
  echo "############### BUILD ABI=$ABI (target=$TARGET arch=$ARCH) ###############"
  export CC=$TC/bin/${TARGET}${API}-clang
  export CXX=$TC/bin/${TARGET}${API}-clang++
  export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip NM=$TC/bin/llvm-nm
  local SYSROOT=$TC/sysroot
  local PREFIX=$ROOT/$ABI/deps
  local OUT=$ROOT/$ABI/out
  mkdir -p "$PREFIX" "$OUT"
  export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig

  echo "=== [$ABI] STAGE: x264 ==="
  if [ -f "$PREFIX/lib/libx264.a" ]; then
    echo "[$ABI] x264 already installed, skipping"
  else
    cd "$SRC"
    [ -d x264 ] || git clone --depth 1 https://code.videolan.org/videolan/x264.git
    cd x264
    make distclean >/dev/null 2>&1 || true
    ./configure --host=$TARGET --prefix="$PREFIX" --enable-static --enable-pic --disable-cli \
      --cross-prefix=$TC/bin/llvm- --sysroot=$SYSROOT --extra-cflags="-fPIC"
    make -j"$J"; make install
  fi
  echo "[$ABI] X264_DONE"

  echo "=== [$ABI] STAGE: x265 ==="
  if [ -f "$PREFIX/lib/libx265.a" ]; then
    echo "[$ABI] x265 already installed, skipping build"
  else
    cd "$SRC"
    [ -d x265_git ] || git clone --depth 1 https://bitbucket.org/multicoreware/x265_git.git
    cd x265_git
    rm -rf build && mkdir build && cd build
    cmake -G "Unix Makefiles" \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
      -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_ASSEMBLY=OFF \
      -DCMAKE_INSTALL_PREFIX="$PREFIX" ../source
    make -j"$J"; make install
  fi
  # NDK's x265 make-install does NOT emit a usable pkgconfig; hand-write one that references libc++
  # (not libstdc++) so ffmpeg's static link test against the C++ x265 lib passes.
  local X265VER
  X265VER=$(grep -m1 -oE '[0-9]+\.[0-9]+' "$PREFIX/include/x265_config.h" 2>/dev/null | head -1 || true)
  [ -n "${X265VER:-}" ] || X265VER=0.0
  mkdir -p "$PREFIX/lib/pkgconfig"
  cat > "$PREFIX/lib/pkgconfig/x265.pc" <<EOF
prefix=$PREFIX
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=\${prefix}/include

Name: x265
Description: H.265/HEVC video encoder
Version: $X265VER
Libs: -L\${libdir} -lx265
Libs.private: -lc++_static -lc++abi -lm -ldl
Cflags: -I\${includedir}
EOF
  grep -q 'c++_static' "$PREFIX/lib/pkgconfig/x265.pc" || fatal "[$ABI] x265.pc libc++ fix did not apply"
  echo "[$ABI] X265_DONE"

  echo "=== [$ABI] STAGE: ffmpeg ==="
  cd "$SRC"
  [ -d ffmpeg ] || git clone --depth 1 https://github.com/FFmpeg/FFmpeg.git ffmpeg
  cd ffmpeg
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --prefix="$OUT" \
    --target-os=android --arch=$ARCH --enable-cross-compile \
    --cc=$CC --cxx=$CXX --ar=$AR --ranlib=$RANLIB --strip=$STRIP --nm=$NM \
    --sysroot=$SYSROOT \
    --pkg-config=$(command -v pkg-config) --pkg-config-flags=--static \
    --extra-cflags="-I$PREFIX/include -O2 -fPIC -fPIE" \
    --extra-ldflags="-L$PREFIX/lib -pie -static-libstdc++ -Wl,-z,max-page-size=16384" \
    --extra-libs="-lm" \
    --enable-gpl --enable-version3 \
    --enable-libx264 --enable-libx265 \
    --enable-jni --enable-mediacodec \
    --disable-shared --enable-static \
    --disable-doc --disable-ffplay --disable-ffprobe \
    --enable-ffmpeg
  make -j"$J"
  local SO=$ROOT/$ABI/libffmpeg.so
  cp ffmpeg "$SO"
  "$STRIP" "$SO" || true

  # ── Self-verify: 16KB LOAD alignment (must be 0x4000) ──
  local ALIGN
  ALIGN=$("$TC/bin/llvm-readelf" -l "$SO" | awk '$1=="LOAD"{print $NF; exit}')
  echo "[$ABI] first LOAD align = $ALIGN"
  [ "$ALIGN" = "0x4000" ] || fatal "[$ABI] LOAD alignment is $ALIGN, expected 0x4000 (16KB)"

  mkdir -p "$JNILIBS/$ABI"
  cp "$SO" "$JNILIBS/$ABI/libffmpeg.so"
  ls -la "$JNILIBS/$ABI/libffmpeg.so"
  echo "[$ABI] ALL_DONE -> $JNILIBS/$ABI/libffmpeg.so"
}

WHICH=${1:-all}
case "$WHICH" in
  arm64-v8a) build_abi arm64-v8a aarch64-linux-android aarch64 ;;
  x86_64)    build_abi x86_64 x86_64-linux-android x86_64 ;;
  all)       build_abi arm64-v8a aarch64-linux-android aarch64
             build_abi x86_64 x86_64-linux-android x86_64 ;;
  *) fatal "unknown ABI '$WHICH' (use arm64-v8a | x86_64 | all)" ;;
esac
echo "BUILD_COMPLETE ($WHICH)"
