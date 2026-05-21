#!/bin/bash
VERSION="v148.0.7778.96-5"
BASE_URL="https://github.com/klzgrad/naiveproxy/releases/download/${VERSION}"

mkdir -p app/src/main/jniLibs
cd app/src/main/jniLibs

for arch in arm64-v8a armeabi-v7a x86 x86_64; do
    echo "Processing ${arch}..."
    mkdir -p ${arch}
    wget -qO plugin.apk "${BASE_URL}/naiveproxy-plugin-${VERSION}-${arch}.apk"
    if ! unzip -p plugin.apk "lib/${arch}/libnaive.so" > "${arch}/libnaive.so"; then
        echo "Trying alternative path in APK for ${arch}..."
        SO_PATH=$(unzip -l plugin.apk | grep -o 'lib/.*libnaive\.so' | head -n 1)
        if [ -n "$SO_PATH" ]; then
            unzip -p plugin.apk "$SO_PATH" > "${arch}/libnaive.so"
        else
            echo "Error: Could not find libnaive.so in APK for ${arch}"
        fi
    fi
    rm -f plugin.apk
done
echo "Done"
