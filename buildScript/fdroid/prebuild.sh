#!/bin/bash
set -euo pipefail
if [ "${THBOX_OFFLINE:-0}" = 1 ]; then
  export GOPROXY=off GOSUMDB=off
fi

buildScript/init/action/gradle.sh

# Build libcore
buildScript/lib/core.sh

# The core intentionally excludes Naive; prepare its packaged plugin too.
bash download_naive.sh
