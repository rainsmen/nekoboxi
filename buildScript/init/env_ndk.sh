#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

# Force install NDK 27 to ensure LLVM 17+ linker support for R_AARCH64_PREL32
_NDK="$ANDROID_HOME/ndk/27.0.12077973"
if [ ! -f "$_NDK/source.properties" ]; then
  if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Installing NDK 27.0.12077973 via sdkmanager (required for cronet-go compatibility)..."
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;27.0.12077973" >/dev/null
  fi
fi

# Verify NDK 27 is available
_NDK=$(ls -d "$ANDROID_HOME/ndk"/27.* 2>/dev/null | sort -V | tail -n 1)
if [ -z "$_NDK" ]; then
  echo "Error: NDK 27 not found. NDK 27 is required for cronet-go compatibility (R_AARCH64_PREL32 relocation support)."
  exit 1
fi

echo "Using NDK: $_NDK"

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
