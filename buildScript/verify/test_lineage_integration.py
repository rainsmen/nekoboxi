"""Exercise Build Tools against temporary test keys, never the project's signing identities."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from lineage import verify_lineage

SDK = Path(os.environ.get('ANDROID_HOME', '/nonexistent'))
TOOLS = SDK / 'build-tools/35.0.1'
ANDROID_JAR = SDK / 'platforms/android-35/android.jar'


HAS_TOOLS = all((TOOLS / tool).is_file() for tool in ('apksigner', 'aapt')) and ANDROID_JAR.is_file() and shutil.which('keytool')
if os.environ.get('CI') and not HAS_TOOLS:
    raise RuntimeError('CI requires Android SDK 35, Build Tools 35.0.1 and JDK before verifier tests')


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], stderr=subprocess.STDOUT, timeout=60)


@unittest.skipUnless(HAS_TOOLS,
                     'Android Build Tools 35.0.1 and JDK are required')
class RealLineageTest(unittest.TestCase):
    def test_binary_lineage_and_final_apk_require_the_expected_root(self):
        with tempfile.TemporaryDirectory(prefix='thbox-synthetic-lineage-') as temporary:
            root = Path(temporary)
            password = 'synthetic-test-only'
            certificates = {}
            def signer(name):
                return ['--ks', root / f'{name}.jks', '--ks-key-alias', name,
                        '--ks-pass', f'pass:{password}', '--key-pass', f'pass:{password}']
            for name in ('old', 'new'):
                run('keytool', '-genkeypair', '-noprompt', '-keystore', root / f'{name}.jks',
                    '-storetype', 'JKS', '-alias', name, '-storepass', password, '-keypass', password,
                    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', f'CN=Synthetic {name}')
                certificate = subprocess.check_output(['keytool', '-exportcert', '-keystore', str(root / f'{name}.jks'),
                                  '-storepass', password, '-alias', name], stderr=subprocess.PIPE, timeout=60)
                certificates[name] = hashlib.sha256(certificate).hexdigest().upper()
            apksigner = TOOLS / 'apksigner'
            lineage = root / 'lineage.bin'
            run(apksigner, 'rotate', '--out', lineage, '--old-signer', *signer('old'), '--new-signer', *signer('new'))
            old, new = certificates['old'], certificates['new']
            self.assertEqual([old, new], verify_lineage(apksigner, lineage, new, old))
            with self.assertRaises(ValueError):
                verify_lineage(apksigner, lineage, new, '0' * 64)
            manifest = root / 'AndroidManifest.xml'
            manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.lineage.fixture" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35"/><application/></manifest>''')
            unsigned = root / 'unsigned.apk'
            run(TOOLS / 'aapt', 'package', '-f', '-M', manifest, '-I', ANDROID_JAR, '-F', unsigned)
            rotated = root / 'rotated.apk'
            run(apksigner, 'sign', *signer('old'), '--next-signer', *signer('new'),
                '--lineage', lineage, '--rotation-min-sdk-version', '28', '--out', rotated, unsigned)
            run(apksigner, 'verify', '--verbose', rotated)
            self.assertEqual([old, new], verify_lineage(apksigner, rotated, new, old))
            with self.assertRaises(ValueError):
                verify_lineage(apksigner, rotated, new)  # Real historical pin rejects these test keys.
            new_only = root / 'new-only.apk'
            run(apksigner, 'sign', *signer('new'), '--out', new_only, unsigned)
            run(apksigner, 'verify', '--verbose', new_only)
            with self.assertRaises(ValueError):
                verify_lineage(apksigner, new_only, new, old)
