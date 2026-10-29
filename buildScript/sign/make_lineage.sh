#!/usr/bin/env bash
# One-time, local: create the v3 rotation lineage (old key -> new key) and print
# the values to store as GitHub Actions secrets. Keys stay outside the repo.
#
# Usage: make_lineage.sh <old.keystore> <old-alias> <new.keystore> <new-alias> <out-lineage.bin>
set -euo pipefail

[ $# -eq 5 ] || { sed -n '2,6p' "$0"; exit 1; }
APKSIGNER=$(ls -d "${ANDROID_HOME}"/build-tools/*/apksigner | sort -V | tail -n 1)

"$APKSIGNER" rotate --out "$5" \
  --old-signer --ks "$1" --ks-key-alias "$2" \
  --new-signer --ks "$3" --ks-key-alias "$4"
"$APKSIGNER" lineage --in "$5" --print-certs
echo
echo "SIGNING_LINEAGE_BASE64: base64 -w0 $5"
echo "OLD_KEYSTORE_BASE64:    base64 -w0 $1"
echo "KEYSTORE_BASE64:        base64 -w0 $3"
