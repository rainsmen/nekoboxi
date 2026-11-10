#!/usr/bin/env python3
"""Verify the final signed APK, resolving compiled resources and hashing bundled data."""
import argparse
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import subprocess
import zipfile
from release import metadata
from lineage import verify_lineage


def require(value, message):
    if not value:
        raise SystemExit(message)


def run(*command):
    return subprocess.check_output([str(x) for x in command], text=True, stderr=subprocess.STDOUT)


def elements(tree):
    """Read aapt's indentation tree so attributes stay attached to their own element."""
    result, stack = [], []
    for line in tree.splitlines():
        depth = len(line) - len(line.lstrip())
        element = re.match(r'\s*E: ([^\s(]+)', line)
        if element:
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = (element[1], {})
            result.append(node)
            stack.append((depth, node))
        attribute = re.match(r'\s*A: ([^\s(=]+)(?:\([^)]*\))?=(.*)', line)
        if attribute and stack:
            stack[-1][1][1][attribute[1]] = attribute[2]
    return result


def resource_values(dump):
    resources, current = {}, None
    for line in dump.splitlines():
        entry = re.match(r'\s*resource (0x[0-9a-fA-F]+)\s', line)
        if entry:
            current = entry[1].lower()
            resources[current] = []
        elif current:
            value = re.match(r'\s*\([^)]*\)\s+(.+)', line)
            if value:
                resources[current].append(value[1])
    return resources


def resolve(raw, resources, seen=()):
    raw = raw.strip()
    reference = re.match(r'@(?:ref/)?(0x[0-9a-fA-F]+)\b', raw)
    if reference:
        key = reference[1].lower()
        require(key not in seen, f'Cyclic APK resource {key}')
        require(resources.get(key), f'Missing APK resource {key}')
        return {value for variant in resources[key] for value in resolve(variant, resources, (*seen, key))}
    file = re.match(r'\(file\)\s+(\S+)\s+type=XML', raw)
    if file:
        return {file[1]}
    literal = re.match(r'"(?:[^"\\]|\\.)*"', raw)
    require(literal, f'Unsupported APK resource value: {raw}')
    return {json.loads(literal[0])}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--package', required=True)
    parser.add_argument('--certificate', required=True)
    parser.add_argument('--version-name')
    parser.add_argument('--require-rotation', action='store_true')
    args = parser.parse_args()
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.1'
    badging = run(tools / 'aapt', 'dump', 'badging', args.apk)
    require(re.search(r"package: name='([^']+)'", badging).group(1) == args.package, 'Unexpected applicationId')
    require(re.search(r"sdkVersion:'([^']+)'", badging).group(1) == '29', 'Unexpected minimum SDK')
    versions = metadata((Path(__file__).resolve().parents[2] / 'nb4a.properties').read_text())
    expected_name = args.version_name or versions['VERSION_NAME']
    expected_code = str(int(versions['VERSION_CODE']) * 5)
    require(re.search(r"versionName='([^']+)'", badging).group(1) == expected_name, 'APK versionName does not match the build metadata')
    require(re.search(r"versionCode='([^']+)'", badging).group(1) == expected_code, 'APK versionCode does not match the build metadata')
    lock = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[1] / 'lib/assets.lock').read_text().splitlines()
                if line and not line.startswith('#'))
    evidence = []
    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        require(abis == {'arm64-v8a'}, f'Unexpected APK ABIs: {abis}')
        for library in ('libgojni.so', 'libnaive.so'):
            name = f'lib/arm64-v8a/{library}'
            require(name in names and apk.getinfo(name).file_size > 0, f'Missing required payload: {name}')
            evidence.append(f'{library} SHA-256 {hashlib.sha256(apk.read(name)).hexdigest()}')
        for asset in ('geoip', 'geosite'):
            version = apk.read(f'assets/sing-box/{asset}.version.txt').decode().strip()
            require(version == lock[f'{asset.upper()}_VERSION'], f'{asset} version does not match assets.lock')
            digest, size = hashlib.sha256(), 0
            with apk.open(f'assets/sing-box/{asset}.db.xz') as compressed, lzma.LZMAFile(compressed) as database:
                while chunk := database.read(1024 * 1024):
                    size += len(chunk)
                    require(size <= 256 * 1024 * 1024, f'{asset} expands beyond the size limit')
                    digest.update(chunk)
            require(size and digest.hexdigest() == lock[f'{asset.upper()}_SHA256'], f'{asset} content does not match assets.lock')
            evidence.append(f'{asset} version {version}; decoded SHA-256 {digest.hexdigest()}')

    resources = resource_values(run(tools / 'aapt2', 'dump', 'resources', args.apk))
    manifest = elements(run(tools / 'aapt', 'dump', 'xmltree', args.apk, 'AndroidManifest.xml'))
    paths = set()
    for name, attributes in manifest:
        if name == 'meta-data' and resolve(attributes.get('android:name', '""'), resources) == {'android.app.shortcuts'}:
            paths.update(resolve(attributes.get('android:resource', '""'), resources))
    require(paths, 'No shortcut metadata in APK manifest')
    for path in paths:
        shortcuts = elements(run(tools / 'aapt', 'dump', 'xmltree', args.apk, path))
        ids = {value for name, attrs in shortcuts if name == 'shortcut'
               for value in resolve(attrs.get('android:shortcutId', '""'), resources)}
        require(ids == {'toggle', 'enable', 'disable', 'scan'}, 'Missing launcher shortcuts')
        intents = [attrs for name, attrs in shortcuts if name == 'intent']
        require(len(intents) == 4, 'Unexpected shortcut intent count')
        for attrs in intents:
            require(resolve(attrs.get('android:targetPackage', '""'), resources) == {args.package}, 'Shortcut target package mismatch')

    verified = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', args.apk)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', verified)
    expected = args.certificate.replace(':', '').strip().upper()
    require(re.fullmatch(r'[0-9A-F]{64}', expected), 'Invalid expected certificate digest')
    require(certificates and all(cert.upper() == expected for cert in certificates), 'APK signing certificate does not match the pinned identity')
    if args.require_rotation:
        try:
            chain = verify_lineage(tools / 'apksigner', args.apk, expected)
        except ValueError as error:
            raise SystemExit(str(error))
        evidence.append('Signing lineage SHA-256: ' + ' -> '.join(chain))
    print(f'APK {args.apk.name}')
    print(f'Version name {expected_name}; version code {expected_code}')
    print(f'Package {args.package}; minSdk 29; ABI arm64-v8a; resolved shortcuts and locked assets verified')
    print('\n'.join(evidence))
    print(f'Certificate SHA-256 {expected}')
    print(f'APK SHA-256 {hashlib.sha256(args.apk.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    main()
