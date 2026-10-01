#!/usr/bin/env bash
# Cross-compile a 16KB-aligned, full-GPL ffmpeg executable for Android — arm64-v8a (device) and
# x86_64 (emulator). Each ABI yields a static-PIE `libsieveffmpeg.so` linking, statically:
#   libx264, libx265                      (H.264 / HEVC software encoders)
#   libmp3lame 3.100, libopus 1.6.1       (mp3-320 / opus-160 + the AV1/VP9 audio track)
#   libvpx 1.17.0 (VP9), SVT-AV1 4.2.0    (vp9-* / webm-vp9, av1-* presets)
#   libwebp 1.6.0                         (webp-anim preset: libwebp + libwebp_anim)
#   + MediaCodec/JNI
# Everything is pinned (git commit or SHA-256-verified release tarball), then verified for the
# x265 libc++ pkg-config fix, 16KB LOAD alignment, and the presence of every --enable-lib* flag
# before being copied under jniLibs/.
#
# Usage:  bash ffbuild.sh [arm64-v8a|x86_64|all]   (default: all)
#         bash ffbuild.sh sources                  (only fetch + verify sources, then exit)
# Output: <repo>/transcode/src/main/jniLibs/<abi>/libsieveffmpeg.so
#
# Env overrides:
#   NDK         Android NDK root          (default /opt/android-sdk/ndk/27.3.13750724)
#   FF_ROOT     scratch/build root        (default /opt/ff; use a user-writable dir if /opt/ff is not)
#   JNILIBS_DIR output jniLibs directory  (default <repo>/transcode/src/main/jniLibs)
#
# Host requirements: git curl tar sha256sum flock make cmake pkg-config perl; nasm or yasm
# (needed by libvpx + SVT-AV1 on x86_64 only).
#
# The two ABIs can be built concurrently (`bash ffbuild.sh arm64-v8a & bash ffbuild.sh x86_64`):
# sources are fetched once under a lock into $FF_ROOT/src and never modified; every build tree
# lives under $FF_ROOT/<abi>/build.
set -euo pipefail

NDK=${NDK:-/opt/android-sdk/ndk/27.3.13750724}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26
ROOT=${FF_ROOT:-/opt/ff}
SRC=$ROOT/src
DL=$ROOT/dl
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JNILIBS=${JNILIBS_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)/transcode/src/main/jniLibs}
J=$(nproc)

# ── Pinned sources ───────────────────────────────────────────────────────────────────────
# Git sources are pinned to the exact commits listed in licenses/FFMPEG_SOURCE.txt.
FFMPEG_URL=https://github.com/FFmpeg/FFmpeg.git
FFMPEG_COMMIT=818cecc6e1afab932cf4d40ef0d7b8cd40311a17
X264_URL=https://code.videolan.org/videolan/x264.git
X264_COMMIT=0480cb05fa188d37ae87e8f4fd8f1aea3711f7ee
X265_URL=https://bitbucket.org/multicoreware/x265_git.git
X265_COMMIT=b81f650e21e8aacbe6a9ad04ce14aefc05b932c0

# Release tarballs, downloaded over https and checked against a pinned SHA-256.
#   LAME 3.100 : sha256 computed from the SourceForge release download; its sha1
#                (64c53b1a4d493237cef5e74944912cd9f98e618d) matches the one SourceForge publishes.
#   Opus 1.6.1 : sha256 equals the value in https://downloads.xiph.org/releases/opus/SHA256SUMS.txt
#   libvpx, SVT-AV1, libwebp publish no SHA-256; values below were computed once from the
#   official release downloads (GitHub tag archive / GitLab tag archive / webmproject.org).
LAME_VER=3.100
LAME_URL=https://downloads.sourceforge.net/project/lame/lame/$LAME_VER/lame-$LAME_VER.tar.gz
LAME_SHA256=ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
OPUS_VER=1.6.1
OPUS_URL=https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz
OPUS_SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
VPX_VER=1.17.0
VPX_URL=https://github.com/webmproject/libvpx/archive/refs/tags/v$VPX_VER.tar.gz
VPX_SHA256=1020f184046187baa2985dbde38e0691f49c44088bca7a1842b0236c6081dc0a
SVTAV1_VER=4.2.0
SVTAV1_URL=https://gitlab.com/AOMediaCodec/SVT-AV1/-/archive/v$SVTAV1_VER/SVT-AV1-v$SVTAV1_VER.tar.gz
SVTAV1_SHA256=c7b13c4a84bd3751aa35fcc72be13e6875467e7c2216879251a486e5b1e4e740
WEBP_VER=1.6.0
WEBP_URL=https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$WEBP_VER.tar.gz
WEBP_SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564

fatal() { echo "FATAL: $*" >&2; exit 1; }

[ -x "$TC/bin/llvm-readelf" ] || fatal "NDK not found at $NDK (set NDK=...)"
mkdir -p "$SRC" "$DL" 2>/dev/null || fatal "cannot create $ROOT (set FF_ROOT=<writable dir>)"

# ── Source fetch helpers (run once, under a lock) ────────────────────────────────────────

# git_pin <url> <commit> <dir>: make <dir> a pristine checkout of exactly <commit>.
git_pin() {
  local url=$1 sha=$2 dir=$3
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" = "$sha" ] \
     && [ -z "$(git -C "$dir" status --porcelain 2>/dev/null || echo dirty)" ]; then
    echo "[src] $(basename "$dir") already at ${sha:0:12}"
    return
  fi
  echo "[src] fetching $(basename "$dir") @ ${sha:0:12}"
  rm -rf "$dir"; mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "$url"
  if ! git -C "$dir" fetch -q --depth 1 origin "$sha"; then
    echo "[src] server refused fetch-by-SHA; falling back to a full fetch"
    git -C "$dir" fetch -q origin
  fi
  git -C "$dir" checkout -q --detach "$sha"
  [ "$(git -C "$dir" rev-parse HEAD)" = "$sha" ] || fatal "$(basename "$dir"): checkout is not $sha"
}

# tarball <name> <url> <sha256> <dir>: download (cached), verify SHA-256, extract pristine into <dir>.
tarball() {
  local name=$1 url=$2 sha=$3 dir=$4
  local f=$DL/$(basename "$url")
  if [ ! -s "$f" ] || ! echo "$sha  $f" | sha256sum -c --status -; then
    echo "[src] downloading $name <- $url"
    rm -f "$f" "$f.part"
    curl -fL --retry 3 --retry-delay 3 -o "$f.part" "$url"
    mv "$f.part" "$f"
  fi
  echo "$sha  $f" | sha256sum -c - >/dev/null || fatal "$name: SHA-256 mismatch for $f (expected $sha)"
  if [ ! -f "$dir/.sieve-sha256" ] || [ "$(cat "$dir/.sieve-sha256")" != "$sha" ]; then
    rm -rf "$dir"; mkdir -p "$dir"
    tar xzf "$f" -C "$dir" --strip-components=1
    echo "$sha" > "$dir/.sieve-sha256"
  fi
  echo "[src] $name verified ($sha)"
}

prepare_sources() {
  exec 9>"$ROOT/.src.lock"
  flock 9
  git_pin "$FFMPEG_URL" "$FFMPEG_COMMIT" "$SRC/ffmpeg"
  git_pin "$X264_URL"   "$X264_COMMIT"   "$SRC/x264"
  git_pin "$X265_URL"   "$X265_COMMIT"   "$SRC/x265_git"
  tarball lame   "$LAME_URL"   "$LAME_SHA256"   "$SRC/lame-$LAME_VER"
  tarball opus   "$OPUS_URL"   "$OPUS_SHA256"   "$SRC/opus-$OPUS_VER"
  tarball libvpx "$VPX_URL"    "$VPX_SHA256"    "$SRC/libvpx-$VPX_VER"
  tarball svtav1 "$SVTAV1_URL" "$SVTAV1_SHA256" "$SRC/SVT-AV1-$SVTAV1_VER"
  tarball libwebp "$WEBP_URL"  "$WEBP_SHA256"   "$SRC/libwebp-$WEBP_VER"
  flock -u 9
}

# ── Per-ABI build ────────────────────────────────────────────────────────────────────────
build_abi() {
  local ABI=$1 TARGET=$2 ARCH=$3
  echo "############### BUILD ABI=$ABI (target=$TARGET arch=$ARCH) ###############"
  export CC=$TC/bin/${TARGET}${API}-clang
  export CXX=$TC/bin/${TARGET}${API}-clang++
  export AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip NM=$TC/bin/llvm-nm
  local SYSROOT=$TC/sysroot
  local PREFIX=$ROOT/$ABI/deps
  local OUT=$ROOT/$ABI/out
  local B=$ROOT/$ABI/build       # all per-ABI build trees
  mkdir -p "$PREFIX/lib/pkgconfig" "$OUT" "$B"
  # PKG_CONFIG_LIBDIR (not just _PATH) so a host-arch lib/pkgconfig dir can never leak into the
  # cross build.
  export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
  export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig

  # Shared CMake arguments for every CMake-built dependency.
  local CMAKE_COMMON=(
    -G "Unix Makefiles"
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib
  )

  echo "=== [$ABI] STAGE: x264 ==="
  if [ -f "$PREFIX/lib/libx264.a" ]; then
    echo "[$ABI] x264 already installed, skipping"
  else
    rm -rf "$B/x264"; cp -a "$SRC/x264" "$B/x264"
    cd "$B/x264"
    ./configure --host=$TARGET --prefix="$PREFIX" --enable-static --enable-pic --disable-cli \
      --cross-prefix=$TC/bin/llvm- --sysroot=$SYSROOT --extra-cflags="-fPIC"
    make -j"$J"; make install
  fi
  echo "[$ABI] X264_DONE"

  echo "=== [$ABI] STAGE: x265 ==="
  if [ -f "$PREFIX/lib/libx265.a" ]; then
    echo "[$ABI] x265 already installed, skipping build"
  else
    rm -rf "$B/x265" && mkdir -p "$B/x265" && cd "$B/x265"
    cmake -G "Unix Makefiles" \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
      -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_ASSEMBLY=OFF \
      -DCMAKE_INSTALL_PREFIX="$PREFIX" "$SRC/x265_git/source"
    make -j"$J"; make install
  fi
  # NDK's x265 make-install does NOT emit a usable pkgconfig; hand-write one that references libc++
  # (not libstdc++) so ffmpeg's static link test against the C++ x265 lib passes.
  local X265VER
  X265VER=$(grep -m1 -oE '[0-9]+\.[0-9]+' "$PREFIX/include/x265_config.h" 2>/dev/null | head -1 || true)
  [ -n "${X265VER:-}" ] || X265VER=0.0
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

  echo "=== [$ABI] STAGE: libmp3lame $LAME_VER ==="
  if [ -f "$PREFIX/lib/libmp3lame.a" ]; then
    echo "[$ABI] libmp3lame already installed, skipping"
  else
    rm -rf "$B/lame" && mkdir -p "$B/lame" && cd "$B/lame"
    # LAME ships no pkg-config file; ffmpeg finds it via -I/-L (extra-cflags/ldflags below).
    # --disable-frontend: library only. Do NOT pass --enable-nasm (x86-only, not PIC-safe).
    "$SRC/lame-$LAME_VER/configure" --host=$TARGET --prefix="$PREFIX" \
      --disable-shared --enable-static --with-pic \
      --disable-frontend --disable-decoder --disable-gtktest
    make -j"$J"; make install
  fi
  echo "[$ABI] LAME_DONE"

  echo "=== [$ABI] STAGE: libopus $OPUS_VER ==="
  if [ -f "$PREFIX/lib/libopus.a" ]; then
    echo "[$ABI] libopus already installed, skipping"
  else
    rm -rf "$B/opus" && mkdir -p "$B/opus" && cd "$B/opus"
    "$SRC/opus-$OPUS_VER/configure" --host=$TARGET --prefix="$PREFIX" \
      --disable-shared --enable-static --with-pic \
      --disable-doc --disable-extra-programs
    make -j"$J"; make install
  fi
  echo "[$ABI] OPUS_DONE"

  echo "=== [$ABI] STAGE: libvpx $VPX_VER (VP9) ==="
  if [ -f "$PREFIX/lib/libvpx.a" ]; then
    echo "[$ABI] libvpx already installed, skipping"
  else
    # NDK r27 has no libpthread.a, so libvpx's generic *-linux-gcc target would fail its
    # `-lpthread` probe and silently build WITHOUT multithreading. The *-android-gcc targets skip
    # that probe ("bionic includes basic pthread functionality"). We drive them with the NDK clang
    # via CC/AS/LD instead of CROSS. x86 android soft-enables --realtime-only, which would turn
    # every `-deadline good` encode into realtime mode, so force it off.
    local VPX_TARGET VPX_EXTRA=()
    case "$ABI" in
      arm64-v8a) VPX_TARGET=arm64-android-gcc; export AS=$CC ;;
      x86_64)    VPX_TARGET=x86_64-android-gcc; VPX_EXTRA+=(--disable-realtime-only) ;;
      *) fatal "libvpx: unsupported ABI $ABI" ;;
    esac
    rm -rf "$B/libvpx" && mkdir -p "$B/libvpx" && cd "$B/libvpx"
    LD=$CC "$SRC/libvpx-$VPX_VER/configure" --target=$VPX_TARGET --prefix="$PREFIX" \
      --enable-static --disable-shared --enable-pic \
      --enable-vp9 --disable-vp8 --enable-multithread --enable-runtime-cpu-detect \
      --disable-examples --disable-tools --disable-docs --disable-unit-tests \
      --disable-install-bins --disable-install-docs "${VPX_EXTRA[@]}"
    unset AS
    make -j"$J"; make install
  fi
  echo "[$ABI] VPX_DONE"

  echo "=== [$ABI] STAGE: SVT-AV1 $SVTAV1_VER ==="
  if [ -f "$PREFIX/lib/libSvtAv1Enc.a" ]; then
    echo "[$ABI] SVT-AV1 already installed, skipping"
  else
    rm -rf "$B/svtav1" && mkdir -p "$B/svtav1"
    # Encoder library only. x86_64 uses nasm for the AVX2/AVX512 kernels (runtime-dispatched).
    cmake -S "$SRC/SVT-AV1-$SVTAV1_VER" -B "$B/svtav1" "${CMAKE_COMMON[@]}" \
      -DBUILD_APPS=OFF
    cmake --build "$B/svtav1" -j"$J"
    cmake --install "$B/svtav1"
  fi
  echo "[$ABI] SVTAV1_DONE"

  echo "=== [$ABI] STAGE: libwebp $WEBP_VER ==="
  if [ -f "$PREFIX/lib/libwebp.a" ] && [ -f "$PREFIX/lib/libwebpmux.a" ]; then
    echo "[$ABI] libwebp already installed, skipping"
  else
    rm -rf "$B/libwebp" && mkdir -p "$B/libwebp"
    # libwebp (encoder) + libwebpmux (needed by ffmpeg's libwebp_anim encoder); no CLI tools.
    cmake -S "$SRC/libwebp-$WEBP_VER" -B "$B/libwebp" "${CMAKE_COMMON[@]}" \
      -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF \
      -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF \
      -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF \
      -DWEBP_BUILD_LIBWEBPMUX=ON
    cmake --build "$B/libwebp" -j"$J"
    cmake --install "$B/libwebp"
  fi
  echo "[$ABI] WEBP_DONE"

  # Bionic has no libpthread (it is part of libc), so a stray `-lpthread` in any .pc Libs.private
  # would break ffmpeg's static link probes. Strip it defensively.
  sed -i 's/ -lpthread//g; s/-lpthread //g' "$PREFIX"/lib/pkgconfig/*.pc
  for pc in opus vpx SvtAv1Enc libwebp libwebpmux x264 x265; do
    [ -f "$PREFIX/lib/pkgconfig/$pc.pc" ] || fatal "[$ABI] missing pkg-config file $pc.pc"
    PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig pkg-config --static --libs $pc >/dev/null \
      || fatal "[$ABI] pkg-config cannot resolve $pc"
  done

  echo "=== [$ABI] STAGE: ffmpeg ==="
  rm -rf "$B/ffmpeg" && mkdir -p "$B/ffmpeg" && cd "$B/ffmpeg"
  "$SRC/ffmpeg/configure" \
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
    --enable-libmp3lame --enable-libopus --enable-libvpx --enable-libsvtav1 --enable-libwebp \
    --enable-jni --enable-mediacodec \
    --disable-shared --enable-static \
    --disable-doc --disable-ffplay --disable-ffprobe \
    --enable-ffmpeg
  make -j"$J"
  local SO=$ROOT/$ABI/libsieveffmpeg.so
  cp ffmpeg "$SO"
  "$STRIP" "$SO" || true

  # ── Self-verify 1: EVERY PT_LOAD segment must be 16KB-aligned (0x4000) ──
  local ALIGNS BAD
  ALIGNS=$("$TC/bin/llvm-readelf" -lW "$SO" | awk '$1=="LOAD"{print $NF}')
  echo "[$ABI] LOAD aligns: $(echo $ALIGNS)"
  [ -n "$ALIGNS" ] || fatal "[$ABI] no LOAD segments found"
  BAD=$(echo "$ALIGNS" | grep -vx '0x4000' || true)
  [ -z "$BAD" ] || fatal "[$ABI] LOAD alignment is not 0x4000 everywhere: $BAD"

  # ── Self-verify 2: every requested library made it into the binary's configure string ──
  # (dump to a file first: `llvm-strings | grep -q` would trip pipefail on SIGPIPE)
  local FLAG STRINGS=$B/ffmpeg-strings.txt
  "$TC/bin/llvm-strings" "$SO" > "$STRINGS"
  for FLAG in --enable-libx264 --enable-libx265 --enable-libmp3lame --enable-libopus \
              --enable-libvpx --enable-libsvtav1 --enable-libwebp --enable-mediacodec --enable-jni; do
    grep -q -- "$FLAG" "$STRINGS" || fatal "[$ABI] $FLAG missing from binary configuration"
  done
  echo "[$ABI] configuration string contains all --enable-lib* flags"

  mkdir -p "$JNILIBS/$ABI"
  cp "$SO" "$JNILIBS/$ABI/libsieveffmpeg.so"
  ls -la "$JNILIBS/$ABI/libsieveffmpeg.so"
  echo "[$ABI] ALL_DONE -> $JNILIBS/$ABI/libsieveffmpeg.so"
}

WHICH=${1:-all}
case "$WHICH" in
  sources)   prepare_sources ;;
  arm64-v8a) prepare_sources; build_abi arm64-v8a aarch64-linux-android aarch64 ;;
  x86_64)    prepare_sources; build_abi x86_64 x86_64-linux-android x86_64 ;;
  all)       prepare_sources
             build_abi arm64-v8a aarch64-linux-android aarch64
             build_abi x86_64 x86_64-linux-android x86_64 ;;
  *) fatal "unknown ABI '$WHICH' (use arm64-v8a | x86_64 | all | sources)" ;;
esac
echo "BUILD_COMPLETE ($WHICH)"
