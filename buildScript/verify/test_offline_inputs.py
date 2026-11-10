"""Exercise offline packaging with synthetic locked assets and a synthetic plugin APK."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class OfflineInputsTest(unittest.TestCase):
    def test_verified_local_inputs_need_no_network_and_bad_inputs_preserve_outputs(self):
        with tempfile.TemporaryDirectory(prefix='thbox-offline-fixtures-') as temporary:
            root = Path(temporary)
            cache = root / 'download cache'
            cache.mkdir()
            (root / 'buildScript/lib').mkdir(parents=True)
            shutil.copy(ROOT / 'buildScript/lib/assets.sh', root / 'buildScript/lib/assets.sh')
            lock = []
            for name in ('geoip', 'geosite'):
                data = f'synthetic {name}'.encode()
                (cache / f'{name}-fixture.db').write_bytes(data)
                lock.extend([f'{name.upper()}_VERSION=fixture', f'{name.upper()}_SHA256={hashlib.sha256(data).hexdigest()}'])
            (root / 'buildScript/lib/assets.lock').write_text('\n'.join(lock))
            script = (ROOT / 'download_naive.sh').read_text()
            import re
            version = re.search(r'^VERSION="([^"]+)"', script, re.M).group(1)
            apk = cache / f'naiveproxy-plugin-{version}-arm64-v8a.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libnaive.so', b'synthetic plugin')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            script = re.sub(r'(\["arm64-v8a"\]=")[0-9a-f]+', r'\g<1>' + digest, script)
            (root / 'download_naive.sh').write_text(script)
            tools = root / 'tools'
            tools.mkdir()
            for name in ('curl', 'wget'):
                tool = tools / name
                tool.write_text('#!/bin/sh\necho forbidden-network >&2\nexit 99\n')
                tool.chmod(0o755)
            env = dict(os.environ, THBOX_OFFLINE='1', THBOX_DOWNLOAD_CACHE=str(cache),
                       PATH=str(tools) + os.pathsep + os.environ['PATH'], NAIVE_ABIS='arm64-v8a')
            def run(name):
                return subprocess.run(['bash', name], cwd=root, env=env, capture_output=True, text=True, timeout=30)
            for name in ('buildScript/lib/assets.sh', 'download_naive.sh'):
                result = run(name)
                self.assertEqual(0, result.returncode, result.stderr)
            installed = root / 'app/src/main/jniLibs/arm64-v8a/libnaive.so'
            self.assertEqual(b'synthetic plugin', installed.read_bytes())
            apk.write_bytes(b'corrupt plugin')
            self.assertNotEqual(0, run('download_naive.sh').returncode)
            self.assertEqual(b'synthetic plugin', installed.read_bytes())
            apk.unlink()
            missing = run('download_naive.sh')
            self.assertNotEqual(0, missing.returncode)
            self.assertIn('Offline Naive plugin missing', missing.stderr)
            asset = root / 'app/src/main/assets/sing-box/geoip.db.xz'
            original = asset.read_bytes()
            (cache / 'geoip-fixture.db').unlink()
            missing = run('buildScript/lib/assets.sh')
            self.assertNotEqual(0, missing.returncode)
            self.assertIn('Offline asset missing', missing.stderr)
            self.assertEqual(original, asset.read_bytes())
