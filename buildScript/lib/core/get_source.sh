#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/rainsmen/singbox.git sing-box
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
# Keep Android pidfd workaround active; Go 1.25 can still probe pidfd_open,
# which Android 10 seccomp kills on some devices.
pidfd_workaround="experimental/libbox/pidfd_android.go"
mkdir -p "$(dirname "$pidfd_workaround")"
if [ ! -f "$pidfd_workaround" ]; then
  cat > "$pidfd_workaround" <<EOF
package libbox

import (
	"os"
	_ "unsafe"
)

// https://github.com/SagerNet/sing-box/issues/3233
// https://github.com/golang/go/issues/70508
// https://github.com/tailscale/tailscale/issues/13452

//go:linkname checkPidfdOnce os.checkPidfdOnce
var checkPidfdOnce func() error

func init() {
	checkPidfdOnce = func() error {
		return os.ErrInvalid
	}
}
EOF
else
  sed -i "1{/^\/\/go:build !go1\.23$/ { N; s/^\/\/go:build !go1\.23\n\n//; }}" "$pidfd_workaround"
fi
if ! grep -q "os.checkPidfdOnce" "$pidfd_workaround"; then
  echo "missing Android pidfd workaround in $pidfd_workaround" >&2
  exit 1
fi
# Downgrade cronet-go to pre-NDK 27 version to avoid relocation 315 linking error
sed -i 's/20260513071958-2faf34666c2c/20260413093659-e4926ba205fa/g' go.mod
sed -i 's/20260513071149-ade33496efb8/20260413092954-cd09eb3e271b/g' go.mod
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
