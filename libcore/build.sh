#!/bin/bash
set -e

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

# NaiveProxy is supplied as the external libnaive.so plugin. Keep the native
# sing-box Naive outbound disabled to avoid Cronet/NDK relocation issues.
if [ "${THBOX_OFFLINE:-0}" = 1 ]; then
  export GOPROXY=off GOSUMDB=off
else
  go mod download
fi
# Shown in the about page and User-Agent. libcore registers no deprecated.Manager,
# so this does not turn sing-box deprecation warnings into fatal errors.
SING_BOX_VERSION=$(cat ../buildScript/lib/core/version.txt)
[[ "$SING_BOX_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid pinned core version" >&2; exit 1; }
export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 29 -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION}" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
