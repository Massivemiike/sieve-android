#!/usr/bin/env bash
# Regenerate the license notices for the codec libraries that ffbuild.sh links statically into
# libsieveffmpeg.so:
#   app/src/main/assets/licenses/CODEC_LICENSES.txt     (About -> Licenses)
#   licenses/CODEC_LICENSES.txt                         (mirror, next to FFMPEG_SOURCE.txt)
#
# Their licenses (BSD-3-Clause for Opus, libvpx and libwebp, BSD-3-Clause-Clear plus the AOMedia
# Patent License for SVT-AV1, LGPL for LAME) require the copyright notice and license text to travel
# with binary copies. The texts are copied VERBATIM from the SHA-256-verified source trees ffbuild.sh
# pins, and the versions and checksums are read from ffbuild.sh itself, so this file cannot drift from
# what is built: bump a pin in ffbuild.sh and this script refuses to run until it is updated.
#
# Usage:  bash codec-licenses.sh
# Env:    FF_ROOT  the ffbuild.sh scratch root holding src/   (default /opt/ff, as ffbuild.sh)
#         If src/ is empty, fetch it first:  bash ffbuild.sh sources   (needs NDK=... like any ffbuild.sh run)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
SRC=${FF_ROOT:-/opt/ff}/src
OUT=$REPO/app/src/main/assets/licenses/CODEC_LICENSES.txt
MIRROR=$REPO/licenses/CODEC_LICENSES.txt

fatal() { echo "FATAL: $*" >&2; exit 1; }

# The pins (LAME_VER/_URL/_SHA256, OPUS_*, VPX_*, SVTAV1_*, WEBP_*, X264_URL/_COMMIT, X265_URL/_COMMIT), read
# from ffbuild.sh in file order so a _URL that mentions $LAME_VER expands. Only plain assignments are eval'd.
eval "$(grep -E '^(LAME|OPUS|VPX|SVTAV1|WEBP)_(VER|URL|SHA256)=[A-Za-z0-9._:/$+{}-]+$|^(X264|X265)_(URL|COMMIT)=[A-Za-z0-9._:/$+{}-]+$' "$HERE/ffbuild.sh")"
for v in LAME OPUS VPX SVTAV1 WEBP; do
  [ -n "$(eval echo "\${${v}_VER:-}")" ] && [ -n "$(eval echo "\${${v}_SHA256:-}")" ] || fatal "ffbuild.sh no longer defines ${v}_VER / ${v}_SHA256"
done

# tarball_tree <dir> <sha256>: the tree ffbuild.sh extracted from the pinned, verified tarball.
tarball_tree() {
  [ -d "$1" ] || fatal "$1 is missing: run  bash $HERE/ffbuild.sh sources  (FF_ROOT=${FF_ROOT:-/opt/ff})"
  [ "$(cat "$1/.sieve-sha256" 2>/dev/null)" = "$2" ] || fatal "$1 is not the tarball pinned in ffbuild.sh (sha256 $2)"
}
git_tree() {
  [ -d "$1/.git" ] || fatal "$1 is missing: run  bash $HERE/ffbuild.sh sources  (FF_ROOT=${FF_ROOT:-/opt/ff})"
  [ "$(git -C "$1" rev-parse HEAD)" = "$2" ] || fatal "$1 is not the commit pinned in ffbuild.sh ($2)"
}
OPUS=$SRC/opus-$OPUS_VER;    tarball_tree "$OPUS" "$OPUS_SHA256"
VPX=$SRC/libvpx-$VPX_VER;    tarball_tree "$VPX" "$VPX_SHA256"
SVT=$SRC/SVT-AV1-$SVTAV1_VER; tarball_tree "$SVT" "$SVTAV1_SHA256"
WEBP=$SRC/libwebp-$WEBP_VER; tarball_tree "$WEBP" "$WEBP_SHA256"
LAME=$SRC/lame-$LAME_VER;    tarball_tree "$LAME" "$LAME_SHA256"
X264=$SRC/x264;              git_tree "$X264" "$X264_COMMIT"
X265=$SRC/x265_git;          git_tree "$X265" "$X265_COMMIT"

# licfile <title> <path>: one verbatim license file.
licfile() {
  [ -f "$2" ] || fatal "missing $2 (did the pinned release move its license files?)"
  printf '\n---------- %s ----------\n\n' "$1"
  cat "$2"
  [ "$(tail -c1 "$2" | od -An -c | tr -d ' ')" = '\n' ] || echo
}
# header <name> <license> <source url> <sha256>
header() {
  printf '\n\n==================== %s ====================\n' "$1"
  printf 'License: %s\nSource:  %s\nSHA-256: %s (checked by ffbuild.sh before every build)\n' "$2" "$3" "$4"
}
# The copyright/permission block at the top of a source header (x264, x265 carry no separate notice file).
srchead() {
  printf '\n---------- %s ----------\n\n' "$1"
  awk '{ print } /^ \*+\/$/ { exit }' "$2"
}

{
  cat <<'TXT'
Codec libraries statically linked into FFmpeg (libsieveffmpeg.so) — copyright notices and licenses
=================================================================================================

Sieve for Android's own FFmpeg, the program the Transcode screen runs (see "Written offer for
source"), statically links the libraries below. Their licenses require that the copyright notice, the
license conditions and the disclaimer accompany binary copies; this file is that notice. The license
texts are copied verbatim from the source release each library is built from.

  FFmpeg           GNU GPL v3 as built (--enable-gpl --enable-version3) — see "GNU GPL v3".
  x264, x265       GNU GPL v2 or later; used under the GPLv3 of the combined program. Their copyright
                   notices are below; the license text is "GNU GPL v3".
  Opus             BSD-3-Clause
  libvpx           BSD-3-Clause + the WebM patent grant
  SVT-AV1          BSD-3-Clause-Clear + the AOMedia Patent License 1.0
  libwebp          BSD-3-Clause + the WebM patent grant
  LAME (mp3lame)   LGPL v2 or later. Section 3 of that license lets a recipient apply the ordinary GNU
                   GPL to a given copy of the library instead; the combined FFmpeg here is GPLv3, so
                   that is how LAME is used. Its own text follows because it must still accompany it.

Each section names the exact release and the SHA-256 that transcode/build-ffmpeg/ffbuild.sh verifies.
This file is generated by transcode/build-ffmpeg/codec-licenses.sh.
TXT

  header "Opus $OPUS_VER (libopus)" "BSD-3-Clause" "$OPUS_URL" "$OPUS_SHA256"
  licfile "COPYING" "$OPUS/COPYING"

  header "libvpx $VPX_VER (VP9)" "BSD-3-Clause + WebM patent grant" "$VPX_URL" "$VPX_SHA256"
  licfile "LICENSE" "$VPX/LICENSE"
  licfile "PATENTS (Additional IP Rights Grant)" "$VPX/PATENTS"

  header "SVT-AV1 $SVTAV1_VER (libsvtav1)" "BSD-3-Clause-Clear + AOMedia Patent License 1.0" "$SVTAV1_URL" "$SVTAV1_SHA256"
  licfile "LICENSE.md" "$SVT/LICENSE.md"
  licfile "LICENSE-BSD2.md" "$SVT/LICENSE-BSD2.md"
  licfile "PATENTS.md (Alliance for Open Media Patent License 1.0)" "$SVT/PATENTS.md"

  header "libwebp $WEBP_VER (libwebp, libwebpmux)" "BSD-3-Clause + WebM patent grant" "$WEBP_URL" "$WEBP_SHA256"
  licfile "COPYING" "$WEBP/COPYING"
  licfile "PATENTS (Additional IP Rights Grant)" "$WEBP/PATENTS"

  header "LAME $LAME_VER (libmp3lame)" "LGPL-2.0-or-later" "$LAME_URL" "$LAME_SHA256"
  licfile "LICENSE (LAME's usage note)" "$LAME/LICENSE"
  licfile "COPYING (GNU Library General Public License, version 2)" "$LAME/COPYING"

  printf '\n\n==================== x264 (libx264) ====================\n'
  printf 'License: GPL-2.0-or-later (also offered commercially by the x264 project)\nSource:  %s\nCommit:  %s\n' "$X264_URL" "$X264_COMMIT"
  srchead "x264.h, top of file" "$X264/x264.h"

  printf '\n\n==================== x265 (libx265) ====================\n'
  printf 'License: GPL-2.0-or-later (also offered commercially by MulticoreWare)\nSource:  %s\nCommit:  %s\n' "$X265_URL" "$X265_COMMIT"
  srchead "source/x265.h, top of file" "$X265/source/x265.h"
} > "$OUT"

cp "$OUT" "$MIRROR"
echo "wrote $OUT ($(wc -c < "$OUT") bytes) and $MIRROR"
