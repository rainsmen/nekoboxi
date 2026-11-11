#!/usr/bin/env python3
"""Reject an AAR whose Wi-Fi callback cannot carry Java exceptions back to Go."""
import argparse
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import zipfile


def verify(aar):
    with tempfile.TemporaryDirectory(prefix='thbox-bridge-verify-') as temporary:
        jar = Path(temporary) / 'classes.jar'
        with zipfile.ZipFile(aar) as archive:
            jar.write_bytes(archive.read('classes.jar'))
            library = Path(temporary) / 'libgojni.so'
            library.write_bytes(archive.read('jni/arm64-v8a/libgojni.so'))
        declaration = subprocess.check_output(
            ['javap', '-classpath', str(jar), 'libcore.BoxPlatformInterface'], text=True)
        if 'java.lang.String wifiState() throws java.lang.Exception;' not in declaration:
            raise SystemExit('Wi-Fi callback is missing the JNI exception contract')
        sdk = Path(os.environ.get('ANDROID_HOME', '/nonexistent'))
        candidates = list(sdk.glob('ndk/*/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump'))
        objdump = str(candidates[-1]) if candidates else shutil.which('llvm-objdump')
        if not objdump:
            raise SystemExit('NDK llvm-objdump is required to verify the native callback')
        assembly = subprocess.check_output([objdump,
            '--disassemble-symbols=cproxylibcore_BoxPlatformInterface_WIFIState', str(library)], text=True)
        if '<go_seq_get_exception>' not in assembly:
            raise SystemExit('Native Wi-Fi callback does not extract and clear Java exceptions')
    print('Java declaration and arm64 JNI Wi-Fi exception handling verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('aar', type=Path)
    verify(parser.parse_args().aar)
