#!/bin/bash
VERSION="v118.0.5993.65-1"
BASE_URL="https://github.com/MatsuriDayo/plugins/releases/download/naive-${VERSION}"

mkdir -p app/src/main/jniLibs
cd app/src/main/jniLibs

for arch in arm64-v8a; do
    echo "Processing ${arch}..."
    mkdir -p ${arch}
    wget -qO plugin.apk "${BASE_URL}/naive-plugin-${VERSION#v}-${arch}.apk"
    unzip -p plugin.apk "lib/${arch}/libnaive.so" > "${arch}/libnaive.so"
    rm -f plugin.apk
done
echo "Done"
