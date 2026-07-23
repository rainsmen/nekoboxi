#!/bin/bash
set -euo pipefail

VERSION="v150.0.7871.63-1"
BASE_URL="https://github.com/klzgrad/naiveproxy/releases/download/${VERSION}"
APK_SHA256="733fbbbebb383a91f42036992c21cfd19b99e089ac3d15d7c077df79fc471a89"

mkdir -p app/src/main/jniLibs
cd app/src/main/jniLibs

for arch in arm64-v8a; do
    echo "Processing ${arch}..."
    mkdir -p "${arch}"
    wget -qO plugin.apk "${BASE_URL}/naiveproxy-plugin-${VERSION}-${arch}.apk"
    echo "${APK_SHA256}  plugin.apk" | sha256sum -c -
    unzip -p plugin.apk "lib/${arch}/libnaive.so" > "${arch}/libnaive.so"
    rm -f plugin.apk
    test -s "${arch}/libnaive.so"
done
echo "Done"
