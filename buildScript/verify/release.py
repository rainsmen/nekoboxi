#!/usr/bin/env python3
"""Bind a release tag to repository metadata and a strictly increasing version code."""
import argparse
from pathlib import Path
import re
import subprocess


def metadata(text):
    return dict(line.split('=', 1) for raw in text.splitlines()
                if (line := raw.strip()) and not line.startswith('#') and '=' in line)


def validate_version(tag, current, history, publish=True):
    if not re.fullmatch(r'v?[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?', tag):
        raise ValueError('Invalid release tag')
    if tag.removeprefix('v') != current['VERSION_NAME'].removeprefix('v'):
        raise ValueError('Release tag must match VERSION_NAME in nb4a.properties')
    code = int(current['VERSION_CODE'])
    if not 0 < code * 5 <= 2100000000:
        raise ValueError('VERSION_CODE is outside the Android range')
    for previous_tag, previous in history if publish else []:
        if previous_tag == tag or previous.get('PACKAGE_NAME') != current['PACKAGE_NAME']:
            continue
        if int(previous['VERSION_CODE']) >= code:
            raise ValueError(f'Increase VERSION_CODE beyond {previous_tag} before releasing')
    return code * 5


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('tag')
    parser.add_argument('--build-only', action='store_true', help='Validate identity without requiring a new release version')
    args = parser.parse_args()
    current = metadata(Path('nb4a.properties').read_text())
    tags = subprocess.check_output(['git', 'tag', '--list'], text=True).splitlines()
    history = []
    for tag in tags:
        if not re.fullmatch(r'v?[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?', tag):
            continue
        result = subprocess.run(['git', 'show', f'{tag}:nb4a.properties'], text=True, capture_output=True)
        if result.returncode == 0:
            history.append((tag, metadata(result.stdout)))
    try:
        code = validate_version(args.tag, current, history, publish=not args.build_only)
    except (ValueError, KeyError) as error:
        raise SystemExit(str(error))
    print(f'Release version {args.tag}; APK versionCode {code}')


if __name__ == '__main__':
    main()
