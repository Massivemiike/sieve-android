#!/usr/bin/env bash
# One-time setup: create the permanent release keystore, wire local signing, and (if `gh` is
# available and authenticated as the repo owner) set the GitHub Actions signing secrets.
#
# Run this ONCE from the repo root:   bash tools/create-keystore.sh
# You will be prompted for a password. BACK UP the resulting .jks + password in two safe places —
# a lost or changed key permanently breaks in-app self-updates.
set -euo pipefail

KEYSTORE_NAME="sieve-release.jks"
ALIAS="sieve"

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool not found. Install a JDK 17 (or run this inside WSL where the Android JDK lives)." >&2
  exit 1
fi

# Store the keystore OUTSIDE the repo (one level up) so it can never be accidentally committed.
KEYSTORE_PATH="$(cd .. && pwd)/$KEYSTORE_NAME"
if [ -e "$KEYSTORE_PATH" ]; then
  echo "ERROR: $KEYSTORE_PATH already exists — refusing to overwrite your signing key." >&2
  exit 1
fi

read -rsp "Choose a strong keystore password: " PW; echo
read -rsp "Re-enter the password: " PW2; echo
[ "$PW" = "$PW2" ] || { echo "Passwords did not match." >&2; exit 1; }

echo "Generating $KEYSTORE_PATH ..."
keytool -genkeypair -v \
  -keystore "$KEYSTORE_PATH" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias "$ALIAS" \
  -storepass "$PW" -keypass "$PW" \
  -dname "CN=Sieve, O=Sieve, C=US"

# Local signing config (git-ignored).
cat > app/keystore.properties <<EOF
storeFile=$KEYSTORE_PATH
storePassword=$PW
keyAlias=$ALIAS
keyPassword=$PW
EOF
echo "Wrote app/keystore.properties (git-ignored) — assembleRelease is now signed locally."

# GitHub Actions secrets (so CI can build signed releases on a tag).
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo "Setting GitHub Actions signing secrets on the current repo ..."
  base64 -w0 "$KEYSTORE_PATH" 2>/dev/null | gh secret set SIGNING_KEYSTORE || \
    base64 "$KEYSTORE_PATH" | tr -d '\n' | gh secret set SIGNING_KEYSTORE
  printf '%s' "$PW"    | gh secret set SIGNING_STORE_PASSWORD
  printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS
  printf '%s' "$PW"    | gh secret set SIGNING_KEY_PASSWORD
  echo "Set SIGNING_KEYSTORE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD."
else
  echo "NOTE: gh not authenticated — skipping GitHub secrets. Set them later with:"
  echo "  base64 -w0 \"$KEYSTORE_PATH\" | gh secret set SIGNING_KEYSTORE"
  echo "  echo -n '<password>' | gh secret set SIGNING_STORE_PASSWORD"
  echo "  echo -n '$ALIAS'     | gh secret set SIGNING_KEY_ALIAS"
  echo "  echo -n '<password>' | gh secret set SIGNING_KEY_PASSWORD"
fi

echo
echo "DONE. Back up $KEYSTORE_PATH and the password NOW (password manager + offline copy)."
