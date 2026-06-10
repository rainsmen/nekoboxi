#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# Native NaiveProxy (cronet-go) is intentionally disabled: it links Chromium
# prebuilt objects that fail with R_AARCH64_PREL32 (relocation 315) under NDK.
# To re-enable, add 'with_naive_outbound' back to -tags (tracked as Phase 3).
# Without the tag, box_include_naive_stub.go provides a no-op registration.
export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w -checklinkname=0' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
