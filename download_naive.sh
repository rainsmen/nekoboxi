#!/bin/bash
set -euo pipefail

VERSION="v150.0.7871.63-1"
BASE_URL="https://github.com/klzgrad/naiveproxy/releases/download/${VERSION}"

declare -A SHA256_MAP=(
    ["arm64-v8a"]="733fbbbebb383a91f42036992c21cfd19b99e089ac3d15d7c077df79fc471a89"
    ["armeabi-v7a"]="d52b01d0a55cd0807fe196e72abd5aa4859a783798b1bc1b3cf1bfa9ad8f7ae4"
    ["x86_64"]="a6800d30bb70798d7b9ad3d0218469c58776c250b462926a7cc2e7795d915f78"
)

mkdir -p app/src/main/jniLibs
cd app/src/main/jniLibs

for arch in arm64-v8a armeabi-v7a x86_64; do
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
