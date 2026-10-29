#!/bin/bash
set -e

# sing-box and libneko are submodules of this repository. The commit recorded in
# the superproject (gitlink) is the single source of truth: it is what gets built
# and what the CI cache key hashes. Fork patches (pidfd workaround, Neko modules)
# are committed in rainsmen/singbox, not applied here.

source "buildScript/init/env.sh"

git submodule sync --quiet
for module in sing-box libneko; do
  commit=$(git ls-files -s "$module" | awk '{print $2}')
  if [ -z "$commit" ]; then
    echo "missing submodule $module" >&2
    exit 1
  fi
  git submodule update --init --checkout "$module"
  actual=$(git -C "$module" rev-parse HEAD)
  if [ "$actual" != "$commit" ]; then
    echo "$module is at $actual, expected $commit (commit or reset the submodule)" >&2
    exit 1
  fi
  echo ">> $module $commit"
done
