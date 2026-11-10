#!/bin/bash
set -e

# sing-box and libneko are submodules of this repository. The commit recorded in
# the superproject (gitlink) is the single source of truth: it is what gets built
# and what the CI cache key hashes. Fork patches (pidfd workaround, Neko modules)
# are committed in rainsmen/singbox, not applied here.

source "buildScript/init/env.sh"

git submodule sync --quiet
for module in sing-box libneko libcore/gomobile; do
  commit=$(git ls-files -s "$module" | awk '{print $2}')
  if [ -z "$commit" ]; then
    echo "missing submodule $module" >&2
    exit 1
  fi
  if [ "${THBOX_OFFLINE:-0}" = 1 ]; then
    git -C "$module" cat-file -e "$commit^{commit}" || { echo "Offline source missing: $module $commit" >&2; exit 1; }
    git submodule update --no-fetch --init --checkout "$module"
  else
    git submodule update --init --checkout "$module"
  fi
  actual=$(git -C "$module" rev-parse HEAD)
  if [ "$actual" != "$commit" ]; then
    echo "$module is at $actual, expected $commit (commit or reset the submodule)" >&2
    exit 1
  fi
  echo ">> $module $commit"
done
