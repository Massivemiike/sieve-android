#!/usr/bin/env bash
# Cross-compile browser impersonation for yt-dlp on Android: curl_cffi 0.16.3 (libcurl-impersonate
# 2.2.2 fused in statically) + its cffi 2.0.0 backend + certifi, for youtubedl-android's embedded
# Termux CPython 3.12 on arm64-v8a. EngineInit unpacks the zip into that Python's site-packages.
#
# Without curl_cffi, yt-dlp cannot impersonate a browser's TLS fingerprint, and sites that check it
# (Vimeo's player pages among them) answer 401/403. The desktop yt-dlp.exe bundles curl_cffi.
#
# Usage:  bash build.sh            (needs: NDK r29, python3.12 + venv, curl; run in Linux/WSL)
# Output: engine/src/main/assets/impersonate/{sieve-impersonate.zip,VERSION}
#         app/src/main/assets/licenses/IMPERSONATE_LICENSES.txt
#
# The HOST needs python3.12 exactly: CPython's configure (--with-build-python) refuses a build python of
# another minor version, so a host whose python3 is 3.13/3.14 must install python3.12 (+ python3.12-venv)
# or point PYBUILD at one: PYBUILD=/path/to/python3.12 bash build.sh
#
# Versions are pinned to what yt-dlp accepts (curl_cffi 0.10-0.16 as of 2026.08.19) and to the
# Python inside youtubedl-android 0.18.1. Bump PYV / the AAR together when that library moves Python.
set -euo pipefail

NDK=${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/29.0.14206865}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26
CC=$TC/bin/aarch64-linux-android$API-clang
STRIP=$TC/bin/llvm-strip
READELF=$TC/bin/llvm-readelf
REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${WORK:-$HOME/sieve-impersonate-build}
DL=$WORK/dl; B=$WORK/build; SITE=$WORK/site
ASSETS=$REPO/engine/src/main/assets/impersonate
LICENSES=$REPO/app/src/main/assets/licenses/IMPERSONATE_LICENSES.txt

PYV=3.12.11; FFIV=3.4.8; CURLV=2.2.2; CCFFIV=0.16.3; CFFIV=2.0.0; CERTV=2026.7.22; YTDLAV=0.18.1

fatal() { echo "FATAL: $*" >&2; exit 1; }

# CPython's configure aborts with "incompatible version ... (expected: 3.12)" deep inside the build when
# the host python is another minor version; say so up front instead.
PYMM=${PYV%.*}
PYBUILD=${PYBUILD:-python$PYMM}
command -v "$PYBUILD" >/dev/null 2>&1 \
  || fatal "$PYBUILD not found: CPython $PYV needs a host python $PYMM (install python$PYMM and python$PYMM-venv, or set PYBUILD=/path/to/python$PYMM)"
PYHOST=$("$PYBUILD" -c 'import sys; print("%d.%d" % sys.version_info[:2])')
[ "$PYHOST" = "$PYMM" ] \
  || fatal "$PYBUILD is Python $PYHOST, but CPython $PYV's configure needs a host python $PYMM (set PYBUILD=/path/to/python$PYMM)"
"$PYBUILD" -c 'import venv, ensurepip' 2>/dev/null \
  || fatal "$PYBUILD has no venv/ensurepip (install python$PYMM-venv)"

[ -x "$CC" ] || fatal "NDK clang not found at $CC (set ANDROID_NDK_HOME)"
mkdir -p "$DL" "$B" "$ASSETS"

fetch() { # url file sha256
  [ -s "$DL/$2" ] || curl -fsSL -o "$DL/$2" "$1"
  echo "$3  $DL/$2" | sha256sum -c --quiet - || fatal "checksum mismatch: $2"
}
fetch "https://www.python.org/ftp/python/$PYV/Python-$PYV.tgz" "Python-$PYV.tgz" \
  7b8d59af8216044d2313de8120bfc2cc00a9bd2e542f15795e1d616c51faf3d6
fetch "https://github.com/libffi/libffi/releases/download/v$FFIV/libffi-$FFIV.tar.gz" "libffi-$FFIV.tar.gz" \
  bc9842a18898bfacb0ed1252c4febcc7e78fa139fd27fdc7a3e30d9d9356119b
fetch "https://github.com/lexiforest/curl-impersonate/releases/download/v$CURLV/libcurl-impersonate-v$CURLV.aarch64-linux-android.tar.gz" \
  "libcurl-impersonate-$CURLV-android.tar.gz" 460a44dc6515cce77d7bbb477a284ea95f612c917ff8d85cd9a7f932a04e9809
fetch "https://files.pythonhosted.org/packages/source/c/curl_cffi/curl_cffi-$CCFFIV.tar.gz" "curl_cffi-$CCFFIV.tar.gz" \
  d15d0c2a35f2d75bec430c28946c2a833f421c85773bdb0795182cc5c515665b
fetch "https://files.pythonhosted.org/packages/source/c/cffi/cffi-$CFFIV.tar.gz" "cffi-$CFFIV.tar.gz" \
  44d1b5909021139fe36001ae048dbdde8214afa20200eda0f64c068cac5d5529
fetch "https://files.pythonhosted.org/packages/py3/c/certifi/certifi-$CERTV-py3-none-any.whl" "certifi-$CERTV-py3-none-any.whl" \
  62f22742b58a1a33014a2b6b706588a8d7e2a88ae7bd1a6ebe8c992928483775
fetch "https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android/library/$YTDLAV/library-$YTDLAV.aar" \
  "youtubedl-android-library-$YTDLAV.aar" 579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef

echo "=== link stubs: the library's own libpython3.12 + libffi (NEEDED entries match its _ctypes)"
L=$B/linklibs; rm -rf "$L"; mkdir -p "$L"
python3 - "$DL/youtubedl-android-library-$YTDLAV.aar" "$L" <<'PY'
import io, sys, zipfile
aar = zipfile.ZipFile(sys.argv[1])
inner = zipfile.ZipFile(io.BytesIO(aar.read("jni/arm64-v8a/libpython.zip.so")))
for name in ("usr/lib/libpython3.12.so.1.0", "usr/lib/libffi.so"):
    with open(f"{sys.argv[2]}/{name.rsplit('/', 1)[1]}", "wb") as f:
        f.write(inner.read(name))
PY
ln -sf libpython3.12.so.1.0 "$L/libpython3.12.so"

echo "=== Python $PYV headers + Android pyconfig.h"
if [ ! -f "$B/py/pyconfig.h" ]; then
  rm -rf "$B/Python-$PYV" "$B/py"; tar xzf "$DL/Python-$PYV.tgz" -C "$B"; mkdir -p "$B/py"
  (cd "$B/py" && CC="$CC" CONFIG_SITE=/dev/null "$B/Python-$PYV/configure" \
     --host=aarch64-linux-android --build=x86_64-pc-linux-gnu --with-build-python="$PYBUILD" --without-ensurepip \
     ac_cv_file__dev_ptmx=yes ac_cv_file__dev_ptc=no ac_cv_buggy_getaddrinfo=no > configure.log 2>&1) \
     || { tail -20 "$B/py/configure.log"; fatal "CPython configure failed"; }
fi
PYINC="-I$B/Python-$PYV/Include -I$B/py"

echo "=== libffi $FFIV headers"
if [ ! -f "$B/ffi/include/ffi.h" ]; then
  rm -rf "$B/libffi-$FFIV" "$B/ffi"; tar xzf "$DL/libffi-$FFIV.tar.gz" -C "$B"; mkdir -p "$B/ffi"
  (cd "$B/ffi" && CC="$CC" "$B/libffi-$FFIV/configure" --host=aarch64-linux-android > configure.log 2>&1) \
     || { tail -20 "$B/ffi/configure.log"; fatal "libffi configure failed"; }
fi

echo "=== sources"
rm -rf "$B/curl" "$B/curl_cffi-$CCFFIV" "$B/cffi-$CFFIV"; mkdir -p "$B/curl"
tar xzf "$DL/libcurl-impersonate-$CURLV-android.tar.gz" -C "$B/curl"
tar xzf "$DL/curl_cffi-$CCFFIV.tar.gz" -C "$B"; tar xzf "$DL/cffi-$CFFIV.tar.gz" -C "$B"
CURL_A=$(find "$B/curl" -name libcurl-impersonate.a | head -1); [ -n "$CURL_A" ] || fatal "no libcurl-impersonate.a"
CURL_INC=$(dirname "$(find "$B/curl" -path '*/curl/curl.h' | head -1)")/..
[ -x "$WORK/venv/bin/python" ] || "$PYBUILD" -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q "cffi==$CFFIV" setuptools

rm -rf "$SITE"; mkdir -p "$SITE"
echo "=== _cffi_backend.cpython-312.so"
"$CC" -shared -fPIC -O2 -DNDEBUG -DFFI_BUILDING=1 -DUSE__THREAD -DHAVE_SYNC_SYNCHRONIZE \
  $PYINC -I"$B/ffi/include" "$B/cffi-$CFFIV/src/c/_cffi_backend.c" \
  -L"$L" -lpython3.12 -lffi -ldl -Wl,--no-undefined -Wl,-z,max-page-size=16384 \
  -o "$SITE/_cffi_backend.cpython-312.so" 2> >(grep -v -E "deprecated|Py_FileSystemDefaultEncoding|^ +[0-9]* \||^ +\||note:|warning generated" >&2)
cp -r "$B/cffi-$CFFIV/src/cffi" "$SITE/cffi"

echo "=== curl_cffi/_wrapper.abi3.so"
CS=$B/curl_cffi-$CCFFIV
"$WORK/venv/bin/python" - "$CS" "$B/_wrapper.c" <<'PY'
import sys
from cffi import FFI
root, out = sys.argv[1], sys.argv[2]
ffi = FFI()
ffi.set_source("curl_cffi._wrapper", '#include "shim.h"\n')  # as curl_cffi's scripts/build.py
with open(f"{root}/ffi/cdef.c") as f:
    ffi.cdef(f.read())
ffi.emit_c_code(out)
PY
for src in "$B/_wrapper.c" "$CS/ffi/shim.c"; do
  "$CC" -c -fPIC -O2 -DNDEBUG $PYINC -I"$CS/include" -I"$CS/ffi" -I"$CURL_INC" "$src" -o "$B/$(basename "$src" .c).o"
done
cp -r "$CS/curl_cffi" "$SITE/curl_cffi"
# clang++ so -static-libstdc++ links the NDK libc++ the archive was built against (the app's Python
# ships no guaranteed libc++_shared); --no-undefined turns a missing symbol into a build error
# rather than a dlopen failure on the phone.
"${CC}++" -shared -fPIC "$B/_wrapper.o" "$B/shim.o" \
  -Wl,--whole-archive "$CURL_A" -Wl,--no-whole-archive \
  -static-libstdc++ -L"$L" -lpython3.12 -lz -lm -ldl \
  -Wl,--no-undefined -Wl,--as-needed -Wl,-z,max-page-size=16384 \
  -o "$SITE/curl_cffi/_wrapper.abi3.so"

# curl_cffi reads its own version through importlib.metadata.
mkdir -p "$SITE/curl_cffi-$CCFFIV.dist-info" "$SITE/cffi-$CFFIV.dist-info"
cp "$CS/PKG-INFO" "$SITE/curl_cffi-$CCFFIV.dist-info/METADATA"
cp "$B/cffi-$CFFIV/PKG-INFO" "$SITE/cffi-$CFFIV.dist-info/METADATA"
(cd "$SITE" && python3 -m zipfile -e "$DL/certifi-$CERTV-py3-none-any.whl" .)
find "$SITE" -name __pycache__ -prune -exec rm -rf {} +

"$STRIP" --strip-unneeded "$SITE/_cffi_backend.cpython-312.so" "$SITE/curl_cffi/_wrapper.abi3.so"
for f in "$SITE/_cffi_backend.cpython-312.so" "$SITE/curl_cffi/_wrapper.abi3.so"; do
  echo "--- $(basename "$f"): $(stat -c %s "$f") bytes"; "$READELF" -d "$f" | grep NEEDED
  "$READELF" -lW "$f" | awk '$1=="LOAD" && $NF!="0x4000" {bad=1} END {exit bad}' || fatal "$f is not 16KB-aligned"
done

echo "=== package"
VERSION="curl_cffi-$CCFFIV cffi-$CFFIV libcurl-impersonate-$CURLV certifi-$CERTV cp312-arm64-v8a"
# Sorted entries + fixed timestamps: the same inputs give a byte-identical zip.
python3 - "$SITE" "$ASSETS/sieve-impersonate.zip" <<'PY'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
paths = sorted(os.path.relpath(os.path.join(d, f), root) for d, _, fs in os.walk(root) for f in fs)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p in paths:
        info = zipfile.ZipInfo(p.replace(os.sep, "/"), date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(root, p), "rb") as f:
            z.writestr(info, f.read())
PY
printf '%s\n' "$VERSION" > "$ASSETS/VERSION"

{
  echo "Browser impersonation for yt-dlp (curl_cffi) — bundled components and their licenses"
  echo "Built by engine/build-impersonate/build.sh. Versions: $VERSION"
  for pair in "curl_cffi (MIT):$CS/LICENSE" "cffi (MIT):$B/cffi-$CFFIV/LICENSE" \
              "certifi (MPL-2.0):$SITE/certifi-$CERTV.dist-info/licenses/LICENSE"; do
    title=${pair%%:*}; file=${pair#*:}
    [ -f "$file" ] || file=$(find "$(dirname "$(dirname "$file")")" -maxdepth 3 -iname 'LICENSE*' | head -1)
    printf '\n\n==================== %s ====================\n\n' "$title"; cat "$file"
  done
  for f in $(find "$B/curl" -maxdepth 2 -name 'LICENSE*' | sort); do
    printf '\n\n==================== libcurl-impersonate: %s ====================\n\n' "$(basename "$f")"; cat "$f"
  done
} > "$LICENSES"

ls -la "$ASSETS" "$LICENSES"
echo "OK: $VERSION"
