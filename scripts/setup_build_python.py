"""Устанавливает проверенный Python для frozen-сборок Windows и macOS."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request


# Published python-build-standalone release with its bundled OpenSSL; the
# runtime check confirms both versions and audits them against OSV.
# https://github.com/astral-sh/python-build-standalone/releases/tag/20261026
RELEASE = '20261026'
CPYTHON_VERSION = '3.12.16'
OPENSSL_VERSION = '3.5.9'
BUILD_SHA256 = {
    'aarch64-apple-darwin': '353d5f1b4fd2715e7053f12f068435d5bbad16b91e057006b5d01e0925709a7e',
    'x86_64-apple-darwin': '37c38fda76fec1fbf310adcbafb7f0d96af13b5511bf8ea954c3aa8eec185734',
    'x86_64-pc-windows-msvc': '1f2ab53880fc67ea323398c6388e05af28addc99a8e389209f08a51fdff4b149',
}
RELEASE_URL = f'https://github.com/astral-sh/python-build-standalone/releases/download/{RELEASE}/'
MARKER = f'{RELEASE}-{CPYTHON_VERSION}'


def build_target(system, machine):
    if system.startswith('linux'):
        return None
    architecture = {'amd64': 'x86_64', 'x86_64': 'x86_64',
                    'arm64': 'aarch64', 'aarch64': 'aarch64'}.get(machine.lower())
    suffix = {'darwin': 'apple-darwin', 'win32': 'pc-windows-msvc'}.get(system)
    target = f'{architecture}-{suffix}'
    if target not in BUILD_SHA256:
        raise RuntimeError(f'Unsupported build platform: {system}/{machine}')
    return target


def extract_verified(archive, destination, expected_sha256):
    with archive.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest()
    if actual != expected_sha256:
        raise RuntimeError('Python distribution SHA-256 mismatch')
    with tarfile.open(archive, 'r:gz') as source:
        source.extractall(destination, filter='data')


def install_python(destination, target):
    # Never overwrite a previous runtime or a directory not created here.
    destination.mkdir(parents=True, exist_ok=False)
    archive = destination / 'distribution.tar.gz'
    try:
        name = f'cpython-{CPYTHON_VERSION}%2B{RELEASE}-{target}-install_only_stripped.tar.gz'
        deadline = time.monotonic() + 180
        size = 0
        with urllib.request.urlopen(RELEASE_URL + name, timeout=15) as response, archive.open('wb') as output:
            while chunk := response.read(256 * 1024):
                size += len(chunk)
                if size > 64 * 1024 * 1024 or time.monotonic() > deadline:
                    raise RuntimeError('Python distribution download exceeded its size or time limit')
                output.write(chunk)
        extract_verified(archive, destination, BUILD_SHA256[target])
        archive.unlink()
        executable = destination / ('python/python.exe' if 'windows' in target else 'python/bin/python3')
        subprocess.run([str(executable), '-c',
                        f'import sys; assert sys.version.split()[0] == {CPYTHON_VERSION!r}; print(sys.version)'],
                       check=True, timeout=30)
        return executable
    except BaseException:
        shutil.rmtree(destination)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True, type=Path)
    args = parser.parse_args()
    target = build_target(sys.platform, platform.machine())
    executable = (install_python(args.destination.resolve(), target)
                  if target else Path(sys.executable).resolve())
    with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as environment:
        environment.write(f'BUILD_PYTHON_EXECUTABLE={executable}\n')
        if target:
            environment.write(f'BUILD_PYTHON_STANDALONE={MARKER}\n')
    print(f'Build Python: {executable}')


if __name__ == '__main__':
    main()
