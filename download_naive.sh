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

mkdir -p app/src/main/jniLibs
cd app/src/main/jniLibs

for arch in "${ABIS[@]}"; do
    echo "Processing ${arch}..."
    mkdir -p "${arch}"
    expected_sha="${SHA256_MAP[$arch]}"
    wget -qO plugin.apk "${BASE_URL}/naiveproxy-plugin-${VERSION}-${arch}.apk"
    echo "${expected_sha}  plugin.apk" | sha256sum -c -
    unzip -p plugin.apk "lib/${arch}/libnaive.so" > "${arch}/libnaive.so"
    rm -f plugin.apk
    test -s "${arch}/libnaive.so"
done
echo "Done"
