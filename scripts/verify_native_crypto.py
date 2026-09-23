"""Проверяет реальные криптобиблиотеки Python перед упаковкой desktop."""
import os
from pathlib import Path
import platform
import re
import ssl
import subprocess
import sys


# OpenSSL advisories of 2026-08-25; compare within each supported branch.
# https://openssl-library.org/news/vulnerabilities/
PATCH_FLOORS = {(3, 0): 22, (3, 4): 7, (3, 5): 8, (3, 6): 4, (4, 0): 2}
# Ubuntu backports fixes without changing the upstream version string.
# https://ubuntu.com/security/notices/USN-8678-1
UBUNTU_JAMMY_FLOOR = '3.0.2-0ubuntu1.29'


def openssl_version(text):
    match = re.fullmatch(r'OpenSSL (\d+)\.(\d+)\.(\d+)(?: [^\r\n]+)?', text)
    if not match:
        raise RuntimeError(f'Unrecognized release OpenSSL version: {text}')
    return tuple(map(int, match.groups()))


def verify_upstream(text):
    version = openssl_version(text)
    floor = PATCH_FLOORS.get(version[:2])
    if floor is None or version[2] < floor:
        raise RuntimeError(f'OpenSSL release needs security review/update: {text}')


def loaded_openssl_paths(maps):
    paths = set()
    for line in maps.splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) != 6:
            continue
        path = fields[5]
        if re.search(r'/lib(?:ssl|crypto)\.so(?:\.|$)', path):
            if not re.fullmatch(r'/[^\r\n]*?/lib(?:ssl|crypto)\.so\.3', path):
                raise RuntimeError('Unexpected or deleted mapped OpenSSL library')
            paths.add(Path(path).resolve(strict=True))
    if {path.name for path in paths} != {'libssl.so.3', 'libcrypto.so.3'} or len(paths) != 2:
        raise RuntimeError('Cannot identify the two loaded system OpenSSL libraries')
    return sorted(paths)


def _run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=15).stdout.strip()


def _package_owner(path):
    # dpkg may record /lib while /proc exposes /usr/lib on merged-/usr systems.
    candidates = [path]
    if str(path).startswith('/usr/lib/'):
        alias = Path(str(path)[4:])
        if alias.resolve() == path:
            candidates.append(alias)
    for candidate in candidates:
        try:
            output = _run(['dpkg-query', '-S', str(candidate)])
        except subprocess.CalledProcessError:
            continue
        if '\n' in output or ': ' not in output:
            continue
        owner, owned_path = output.split(': ', 1)
        if re.fullmatch(r'libssl3(?::[a-z0-9-]+)?', owner) and Path(owned_path).resolve() == path:
            return owner
    raise RuntimeError(f'Loaded OpenSSL library is not owned by Ubuntu libssl3: {path}')


def verify_ubuntu_backport(version_text, maps, distro):
    if openssl_version(version_text) != (3, 0, 2) or distro.get('ID') != 'ubuntu' or distro.get('VERSION_ID') != '22.04':
        raise RuntimeError('No reviewed distribution backport for this OpenSSL runtime')
    records = []
    for path in loaded_openssl_paths(maps):
        owner = _package_owner(path)
        output = _run(['dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${db:Status-Status}', owner])
        parts = output.split('\t')
        if len(parts) != 3 or parts[0] != owner or parts[2] != 'installed' or not parts[1].startswith('3.0.2-0ubuntu1.'):
            raise RuntimeError('Unexpected Ubuntu libssl3 package inventory')
        _run(['dpkg', '--compare-versions', parts[1], 'ge', UBUNTU_JAMMY_FLOOR])
        records.append((str(path), owner, parts[1]))
    if len({version for _, _, version in records}) != 1:
        raise RuntimeError('Loaded OpenSSL libraries have different package versions')
    return records


def main():
    from cryptography.hazmat.backends.openssl.backend import backend

    print('Python:', platform.python_version())
    print('Python ssl:', ssl.OPENSSL_VERSION)
    crypto_version = backend.openssl_version_text()
    print('cryptography:', crypto_version)
    standalone = os.environ.get('BUILD_PYTHON_STANDALONE')
    if standalone and (standalone != '20260901-3.12.14' or sys.version_info[:3] != (3, 12, 14)):
        raise RuntimeError('Build interpreter does not match the pinned standalone distribution')
    try:
        verify_upstream(ssl.OPENSSL_VERSION)
    except RuntimeError:
        if not sys.platform.startswith('linux'):
            raise
        records = verify_ubuntu_backport(ssl.OPENSSL_VERSION,
                                         Path('/proc/self/maps').read_text(),
                                         platform.freedesktop_os_release())
        for path, owner, version in records:
            print(f'Ubuntu security backport: {owner} {version}; loaded {path}')
    # Wheel/static cryptography has its own OpenSSL: a fixed system package
    # must never excuse an outdated library embedded in this extension.
    verify_upstream(crypto_version)
    print('Native OpenSSL verification passed')


if __name__ == '__main__':
    main()
