#!/bin/bash
set -euo pipefail

# Upstream keeps plugin APKs only on the latest release; older tags return 404.
VERSION="v154.0.8037.49-5"
BASE_URL="https://github.com/klzgrad/naiveproxy/releases/download/${VERSION}"

declare -A SHA256_MAP=(
    ["arm64-v8a"]="3a532e6894d87c1fca5c527d3598892627829c76cb5b75ed831555f384841bd0"
    ["armeabi-v7a"]="9acbf08afea2d35aad492d5312444b6aecd5fafa41f29d794d4821549f330da5"
    ["x86_64"]="af3e8070e882f0008b6511394ae4d3737ece508e9b34abee94cb2f59ddb5e940"
)

# Only arm64-v8a is packaged (see splits.abi in buildSrc/src/main/kotlin/Helpers.kt).
# Override with NAIVE_ABIS="arm64-v8a armeabi-v7a x86_64" if more ABIs are enabled.
read -r -a ABIS <<< "${NAIVE_ABIS:-arm64-v8a}"

# Resolve local inputs before any directory change; offline builds never fetch.
if [ -n "${THBOX_DOWNLOAD_CACHE:-}" ]; then
  mkdir -p "$THBOX_DOWNLOAD_CACHE"
  THBOX_DOWNLOAD_CACHE=$(realpath "$THBOX_DOWNLOAD_CACHE")
fi
naive_tmp=$(mktemp -d)
trap 'rm -rf "$naive_tmp"' EXIT
mkdir -p app/src/main/jniLibs

for arch in "${ABIS[@]}"; do
    echo "Processing ${arch}..."
    plugin_name="naiveproxy-plugin-${VERSION}-${arch}.apk"
    expected_sha="${SHA256_MAP[$arch]}"
    if [ -n "${THBOX_DOWNLOAD_CACHE:-}" ] && [ -f "$THBOX_DOWNLOAD_CACHE/$plugin_name" ]; then
        cp "$THBOX_DOWNLOAD_CACHE/$plugin_name" "$naive_tmp/plugin.apk"
    elif [ "${THBOX_OFFLINE:-0}" = 1 ]; then
        echo "Offline Naive plugin missing: $plugin_name in THBOX_DOWNLOAD_CACHE" >&2; exit 1
    else
        wget -qO "$naive_tmp/plugin.apk" "${BASE_URL}/$plugin_name"
    fi
    echo "${expected_sha}  $naive_tmp/plugin.apk" | sha256sum -c -
    if [ -n "${THBOX_DOWNLOAD_CACHE:-}" ]; then
        cp "$naive_tmp/plugin.apk" "$THBOX_DOWNLOAD_CACHE/$plugin_name"
    fi
    unzip -p "$naive_tmp/plugin.apk" "lib/${arch}/libnaive.so" > "$naive_tmp/libnaive.so"
    test -s "$naive_tmp/libnaive.so"
    mkdir -p "app/src/main/jniLibs/$arch"
    install -m 644 "$naive_tmp/libnaive.so" "app/src/main/jniLibs/$arch/libnaive.so"

done
echo "Done"
