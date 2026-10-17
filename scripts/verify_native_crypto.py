"""Проверяет реальные криптобиблиотеки Python перед упаковкой desktop."""
import os
from pathlib import Path
import platform
import re
import ssl
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_dependencies as osv  # noqa: E402
import setup_build_python as pinned  # noqa: E402


# Offline floor from the OpenSSL advisories of 2026-09-29; main() also audits
# the loaded versions against OSV, which covers advisories published later.
# https://openssl-library.org/news/vulnerabilities/
PATCH_FLOORS = {(3, 0): 23, (3, 4): 8, (3, 5): 9, (3, 6): 5, (4, 0): 3}
# Ubuntu backports fixes without changing the upstream version string.
# https://ubuntu.com/security/notices/USN-8847-1
UBUNTU_JAMMY_FLOOR = '3.0.2-0ubuntu1.30'
# Reviewed OSV findings in libraries of the standalone build Python that the
# executables do not contain; any other finding still fails.
NOT_AFFECTED = {
    (osv.EXPAT_GIT, 'CVE-2026-102633'): 'pyexpat and xml are excluded from the executables',
    (osv.EXPAT_GIT, 'CVE-2026-77214'): 'pyexpat and xml are excluded from the executables',
}


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
    if standalone and (standalone != pinned.MARKER
                       or '.'.join(map(str, sys.version_info[:3])) != pinned.CPYTHON_VERSION
                       or openssl_version(ssl.OPENSSL_VERSION) != openssl_version(f'OpenSSL {pinned.OPENSSL_VERSION}')):
        raise RuntimeError('Build interpreter does not match the pinned standalone distribution')
    components = [osv.cpython(platform.python_version(), 'runtime')]
    if standalone:
        # The standalone build Python bundles Expat everywhere and zlib on
        # Windows; Linux uses Ubuntu packages and macOS links Apple's libz.
        import pyexpat
        expat = pyexpat.EXPAT_VERSION.removeprefix('expat_').replace('.', '_')
        components.append(osv.Dependency('GIT', osv.EXPAT_GIT, f'R_{expat}', 'runtime'))
        if sys.platform == 'win32':
            import zlib
            components.append(osv.Dependency('GIT', osv.ZLIB_GIT, f'v{zlib.ZLIB_RUNTIME_VERSION}', 'runtime'))
    try:
        verify_upstream(ssl.OPENSSL_VERSION)
        components.append(osv.openssl('.'.join(map(str, openssl_version(ssl.OPENSSL_VERSION))), 'runtime'))
    except RuntimeError:
        if not sys.platform.startswith('linux'):
            raise
        records = verify_ubuntu_backport(ssl.OPENSSL_VERSION,
                                         Path('/proc/self/maps').read_text(),
                                         platform.freedesktop_os_release())
        for path, owner, version in records:
            print(f'Ubuntu security backport: {owner} {version}; loaded {path}')
        components.extend(osv.Dependency(osv.UBUNTU_JAMMY, 'openssl', version, 'runtime') for _, _, version in records)
    # Wheel/static cryptography has its own OpenSSL: a fixed system package
    # must never excuse an outdated library embedded in this extension.
    verify_upstream(crypto_version)
    components.append(osv.openssl('.'.join(map(str, openssl_version(crypto_version))), 'runtime'))
    findings = osv.audit_native(components)
    for dep, identifier in findings:
        reason = NOT_AFFECTED.get((dep.name, identifier))
        print(f'{dep.name} {dep.version}: https://osv.dev/vulnerability/{identifier}'
              + (f' (reviewed, not affected: {reason})' if reason else ''))
    if any((dep.name, identifier) not in NOT_AFFECTED for dep, identifier in findings):
        raise RuntimeError('Loaded native runtime has known vulnerabilities')
    print('OSV:', ', '.join(f'{dep.name.rsplit("/", 1)[-1]} {dep.version}' for dep in sorted(set(components))),
          '- no unreviewed vulnerabilities')
    print('Native OpenSSL verification passed')


if __name__ == '__main__':
    main()
