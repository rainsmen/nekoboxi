#!/bin/bash
set -euo pipefail

buildScript/init/action/gradle.sh

# Build libcore
buildScript/lib/core.sh
