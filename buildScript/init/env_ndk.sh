#!/usr/bin/env bash
# One NDK for local and CI builds; never silently substitute another version.
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/.local/toolchain/sdk" "$HOME/.local/lib/android/sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$candidate" ]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
THBOX_NDK_VERSION=28.2.13676358
THBOX_NDK="$ANDROID_HOME/ndk/$THBOX_NDK_VERSION"
if [ ! -f "$THBOX_NDK/source.properties" ]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$THBOX_NDK_VERSION" </dev/null
fi
[ -f "$THBOX_NDK/source.properties" ] || { echo "Missing NDK $THBOX_NDK_VERSION" >&2; exit 1; }
export ANDROID_NDK_HOME="$THBOX_NDK"
export NDK="$THBOX_NDK"
