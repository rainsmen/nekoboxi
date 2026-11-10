#!/usr/bin/env bash
set -euo pipefail
if [ "${THBOX_OFFLINE:-0}" = 1 ]; then
  export GOPROXY=off GOSUMDB=off
fi
source ../buildScript/init/env_ndk.sh
GOPATH=${GOPATH:-$(go env GOPATH)}
expected=$(git -C .. ls-files -s libcore/gomobile | awk '{print $2}')
actual=$(git -C gomobile rev-parse HEAD)
[[ -n "$expected" && "$actual" == "$expected" ]] || { echo "gomobile gitlink mismatch" >&2; exit 1; }
# Rebuild from the pinned local source. Go's build cache makes this inexpensive.
(cd gomobile && go install ./cmd/gomobile ./cmd/gobind)
mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
export GOBIND=gobind-matsuri
"$GOPATH/bin/gomobile-matsuri" init
