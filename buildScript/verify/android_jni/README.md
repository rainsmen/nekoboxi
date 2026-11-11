# Android JNI Wi-Fi exception regression

This isolated test uses the same pinned gomobile generator and runtime as libcore.
It does not use user profiles or connect to a proxy server. Run it on a dedicated
x86_64 Android 12 emulator with API 35 build tools installed:

```sh
ANDROID_HOME=/path/to/sdk THBOX_TEST_SERIAL=emulator-5554 \
  bash buildScript/verify/android_jni/run.sh
```

Put the project's Go 1.25.14 and JDK 17 on PATH. Build the pinned gomobile tools via
`libcore/init.sh` first. The runner installs only `probe.thbox.jni`, intentionally
crashes the old callback scenario and retains evidence under `/tmp`.

The three comparisons use a real WifiManager permission failure:

1. No ACCESS_WIFI_STATE and a `string`-only Go callback: Android 12 reproduces
   `Unknown reference: 42` followed by SIGABRT.
2. No permission and `(string, error)` callback: 1,000 exceptions are caught;
   every subsequent owner callback succeeds, with forced GC every ten iterations.
3. Permission declared, old callback: all 1,000 calls succeed without exceptions.

This demonstrates the reported failure mechanism on ART. It is not a full VPN,
phone/vendor, remote server or physical network-switching acceptance test.
The production AAR contract and final APK permission are separately checked by
`native_bridge.py` and `apk.py` in Preview/Release CI.
