#!/bin/bash

source ../buildScript/init/env_ndk.sh

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Clear gomobile cache to ensure fresh NDK detection
echo "Clearing gomobile cache at $GOPATH/pkg/gomobile..."
rm -rf "$GOPATH/pkg/gomobile" 2>/dev/null

# Install gomobile
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
	git checkout origin/master2
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

echo "Using NDK: $ANDROID_NDK_HOME"
echo "gomobile init with ANDROID_NDK_HOME=$ANDROID_NDK_HOME..."
GOBIND=gobind-matsuri gomobile-matsuri init
