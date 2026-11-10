#!/usr/bin/env python3
"""Verify the exact release signing history, independently of the current signer pin."""
import argparse
from pathlib import Path
import re
import subprocess

HISTORICAL_CERTIFICATE = '300310D26448D45EF8509A926F36E58D9F6E244F039F8143B4E2BEECA80A9CCF'


def digest(value):
    value = value.replace(':', '').strip().upper()
    if not re.fullmatch(r'[0-9A-F]{64}', value):
        raise ValueError('Invalid certificate SHA-256 digest')
    return value


def validate_output(output, current, previous=HISTORICAL_CERTIFICATE):
    entries = re.findall(r'^Signer #([1-9][0-9]*) in lineage certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$', output, re.MULTILINE)
    expected = [digest(previous), digest(current)]
    if expected[0] == expected[1]:
        raise ValueError('Rotation requires different old and new certificates')
    if [int(index) for index, _ in entries] != [1, 2] or [cert.upper() for _, cert in entries] != expected:
        raise ValueError('Signing lineage must contain exactly the pinned historical certificate followed by the current certificate')
    return expected


def verify_lineage(apksigner, source, current, previous=HISTORICAL_CERTIFICATE):
    result = subprocess.run([str(apksigner), 'lineage', '--in', str(source), '--print-certs'],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise ValueError('Input does not contain a readable signing lineage')
    return validate_output(result.stdout, current, previous)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--certificate', required=True)
    args = parser.parse_args()
    try:
        chain = verify_lineage(args.apksigner, args.source, args.certificate)
    except ValueError as error:
        raise SystemExit(str(error))
    print('Signing lineage SHA-256: ' + ' -> '.join(chain))


if __name__ == '__main__':
    main()
