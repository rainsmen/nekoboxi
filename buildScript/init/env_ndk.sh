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

# Force install NDK 28 to test newer LLVM linker support for cronet-go relocation 315
_NDK_VERSION="28.2.13676358"
_NDK="$ANDROID_HOME/ndk/$_NDK_VERSION"
if [ ! -f "$_NDK/source.properties" ]; then
  if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Installing NDK $_NDK_VERSION via sdkmanager (required for native Naive/cronet-go POC)..."
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$_NDK_VERSION" >/dev/null
  fi
fi

# Verify NDK 28 is available
_NDK=$(ls -d "$ANDROID_HOME/ndk"/28.* 2>/dev/null | sort -V | tail -n 1)
if [ -z "$_NDK" ]; then
  echo "Error: NDK 28 not found. NDK 28 is required for native Naive/cronet-go POC."
  exit 1
fi

echo "Using NDK: $_NDK"

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
