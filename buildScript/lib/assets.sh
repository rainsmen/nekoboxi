#!/usr/bin/env bash
set -euo pipefail
source buildScript/lib/assets.lock
asset_tmp=$(mktemp -d)
trap 'rm -rf "$asset_tmp"' EXIT
fetch_asset() {
  local name=$1 version=$2 digest=$3
  local cached="${THBOX_DOWNLOAD_CACHE:-}/$name-$version.db"
  if [ -n "${THBOX_DOWNLOAD_CACHE:-}" ] && [ -f "$cached" ]; then
    cp "$cached" "$asset_tmp/$name.db"
  elif [ "${THBOX_OFFLINE:-0}" = 1 ]; then
    echo "Offline asset missing: $name-$version.db in THBOX_DOWNLOAD_CACHE" >&2; return 1
  else
    curl --fail --location --silent --show-error --retry 3 --max-time 120 \
      "https://github.com/SagerNet/sing-$name/releases/download/$version/$name.db" -o "$asset_tmp/$name.db"
  fi
  printf '%s  %s\n' "$digest" "$asset_tmp/$name.db" | sha256sum --check --status
  if [ -n "${THBOX_DOWNLOAD_CACHE:-}" ]; then
    mkdir -p "$THBOX_DOWNLOAD_CACHE"
    cp "$asset_tmp/$name.db" "$cached"
  fi
  xz -9 "$asset_tmp/$name.db"
  printf '%s' "$version" > "$asset_tmp/$name.version.txt"
}
fetch_asset geoip "$GEOIP_VERSION" "$GEOIP_SHA256"
fetch_asset geosite "$GEOSITE_VERSION" "$GEOSITE_SHA256"
mkdir -p app/src/main/assets/sing-box
cp "$asset_tmp/"* app/src/main/assets/sing-box/
