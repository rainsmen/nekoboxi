#!/usr/bin/env bash
# Runs only the isolated probe APK on a dedicated x86_64 Android emulator.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${THBOX_TEST_SERIAL:?Select the dedicated emulator with THBOX_TEST_SERIAL}"
[[ "$THBOX_TEST_SERIAL" == emulator-* ]] || { echo "Use a dedicated emulator for this crash regression" >&2; exit 1; }
repo=$(git rev-parse --show-toplevel)
source_dir="$repo/buildScript/verify/android_jni"
work=$(mktemp -d /tmp/thbox-art-regression-XXXXXX)
echo "Evidence directory: $work"
cp "$source_dir/go.mod" "$source_dir/probe.go" "$work/"
cp "$repo/libcore/go.sum" "$work/go.sum"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export GOBIND="$(go env GOPATH)/bin/gobind-matsuri"
(cd "$work" && "$(go env GOPATH)/bin/gomobile-matsuri" bind -target android/amd64 -androidapi 29 -cache "$work/cache" -o "$work/probe.aar" .)
mkdir -p "$work/apk/lib/x86_64" "$work/classes" "$work/dex" "$work/permitted"
unzip -p "$work/probe.aar" classes.jar > "$work/classes.jar"
unzip -p "$work/probe.aar" jni/x86_64/libgojni.so > "$work/apk/lib/x86_64/libgojni.so"
javac -source 8 -target 8 -cp "$ANDROID_HOME/platforms/android-35/android.jar:$work/classes.jar" -d "$work/classes" "$source_dir/src/probe/ProbeActivity.java"
tools="$ANDROID_HOME/build-tools/35.0.1"
"$tools/d8" --min-api 29 --lib "$ANDROID_HOME/platforms/android-35/android.jar" --output "$work/dex" "$work"/classes/probe/*.class "$work/classes.jar"
cp "$work/dex/classes.dex" "$work/apk/"
keytool -genkeypair -noprompt -keystore "$work/test.jks" -alias test -storepass synthetic-only -keypass synthetic-only -dname CN=Synthetic -keyalg RSA -validity 2 > "$work/keygen.log" 2>&1
build_apk() {
    "$tools/aapt" package -f -M "$1" -I "$ANDROID_HOME/platforms/android-35/android.jar" -F "$work/unsigned.apk" "$work/apk"
    "$tools/apksigner" sign --ks "$work/test.jks" --ks-key-alias test --ks-pass pass:synthetic-only --key-pass pass:synthetic-only --out "$work/probe.apk" "$work/unsigned.apk"
    adb -s "$THBOX_TEST_SERIAL" install -r "$work/probe.apk"
}
adb -s "$THBOX_TEST_SERIAL" uninstall probe.thbox.jni >/dev/null 2>&1 || true
# Match a production runtime; CheckJNI terminates at the earlier pending-exception error.
adb -s "$THBOX_TEST_SERIAL" shell setprop debug.checkjni 0
build_apk "$source_dir/AndroidManifest.xml"
run_probe() {
    local mode="$1" expected="$2" evidence="$3"
    adb -s "$THBOX_TEST_SERIAL" shell am force-stop probe.thbox.jni
    adb -s "$THBOX_TEST_SERIAL" logcat -c
    adb -s "$THBOX_TEST_SERIAL" shell am start -n probe.thbox.jni/probe.ProbeActivity --es mode "$mode"
    for ((attempt=0; attempt<60; attempt++)); do
        adb -s "$THBOX_TEST_SERIAL" logcat -d -s ThBoxArtProbe:I go/Seq:F libc:F AndroidRuntime:E > "$work/$evidence.log"
        if grep -qF "$expected" "$work/$evidence.log"; then cat "$work/$evidence.log"; return; fi
        sleep 1
    done
    cat "$work/$evidence.log"
    echo "Probe failed: $mode" >&2
    return 1
}
run_probe old-real 'Unknown reference: 42' denied-old
run_probe safe-real 'safe-real completed=1000 caught=1000' denied-safe
sed 's/<application/<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"\/><application/' "$source_dir/AndroidManifest.xml" > "$work/permitted/AndroidManifest.xml"
build_apk "$work/permitted/AndroidManifest.xml"
run_probe old-real 'old-real completed=1000 caught=0' permitted-old
adb -s "$THBOX_TEST_SERIAL" shell am force-stop probe.thbox.jni
echo "ART exception regression passed; evidence: $work"
