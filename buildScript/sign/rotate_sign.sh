#!/usr/bin/env bash
# Re-sign APKs with APK Signature Scheme v3 key rotation (old key -> new key).
#
# Installed builds signed by the old key upgrade in place, and devices switch to
# trusting the new key after installing a rotated build. AGP cannot sign with a
# lineage, so this runs on the APKs Gradle produced.
#
# Usage: rotate_sign.sh <apk>...
# Env:   OLD_KEYSTORE_BASE64 OLD_KEYSTORE_PASS OLD_ALIAS_NAME [OLD_ALIAS_PASS]
#        KEYSTORE_BASE64 KEYSTORE_PASS ALIAS_NAME [ALIAS_PASS]
#        SIGNING_LINEAGE_BASE64
#        EXPECTED_CERT_SHA256 (optional, new signer certificate, hex without colons)
set -euo pipefail

#        REQUIRE_ROTATION=1 makes missing secrets an error (release builds)
if [ -z "${OLD_KEYSTORE_BASE64:-}" ] || [ -z "${SIGNING_LINEAGE_BASE64:-}" ] || [ -z "${KEYSTORE_BASE64:-}" ]; then
  if [ "${REQUIRE_ROTATION:-}" = "1" ]; then
    echo "rotate_sign: OLD_KEYSTORE_BASE64, KEYSTORE_BASE64 and SIGNING_LINEAGE_BASE64 are required" >&2
    exit 1
  fi
  echo "rotate_sign: rotation secrets not set, keeping Gradle signature"
  exit 0
fi

APKSIGNER=$(ls -d "${ANDROID_HOME}"/build-tools/*/apksigner | sort -V | tail -n 1)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "$OLD_KEYSTORE_BASE64" | base64 -d > "$WORK/old.keystore"
echo "$KEYSTORE_BASE64" | base64 -d > "$WORK/new.keystore"
echo "$SIGNING_LINEAGE_BASE64" | base64 -d > "$WORK/lineage.bin"
export OLD_ALIAS_PASS="${OLD_ALIAS_PASS:-$OLD_KEYSTORE_PASS}"
export ALIAS_PASS="${ALIAS_PASS:-$KEYSTORE_PASS}"

for apk in "$@"; do
  "$APKSIGNER" sign \
    --ks "$WORK/old.keystore" --ks-key-alias "$OLD_ALIAS_NAME" \
    --ks-pass env:OLD_KEYSTORE_PASS --key-pass env:OLD_ALIAS_PASS \
    --next-signer \
    --ks "$WORK/new.keystore" --ks-key-alias "$ALIAS_NAME" \
    --ks-pass env:KEYSTORE_PASS --key-pass env:ALIAS_PASS \
    --lineage "$WORK/lineage.bin" \
    --rotation-min-sdk-version 28 \
    "$apk"
  certs=$("$APKSIGNER" verify --print-certs -v "$apk")
  echo "$certs" | grep -E "Verified using v3|Number of signers|certificate SHA-256" || true
  if [ -n "${EXPECTED_CERT_SHA256:-}" ] && ! echo "$certs" | tr -d ':' | grep -qi "SHA-256 digest ${EXPECTED_CERT_SHA256//:/}"; then
    echo "rotate_sign: $apk is not signed by the expected certificate" >&2
    exit 1
  fi
done
