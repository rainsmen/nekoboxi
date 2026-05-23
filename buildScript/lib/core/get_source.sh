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
if ! grep -q "go:build !go1.23" experimental/libbox/pidfd_android.go; then
  sed -i '1i //go:build !go1.23\n' experimental/libbox/pidfd_android.go || true
fi
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
