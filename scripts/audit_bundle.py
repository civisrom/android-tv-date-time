#!/usr/bin/env python3
"""Audits every file PyInstaller packed into the desktop executable against OSV.

Each bundled file is traced to its source: a Python distribution, a Debian
package, the build Python runtime, the OS or this repository. A file with no
known source fails the audit, so a new library cannot ship unreviewed.
"""
import argparse
import ast
from importlib import metadata
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_dependencies as osv  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TOC_TYPES = {'BINARY', 'DATA', 'EXTENSION', 'PYMODULE', 'PYSOURCE'}


def _absolute(path):
    # Classify a file by the path PyInstaller took it from, not a symlink target.
    return Path(os.path.abspath(path))


def toc_sources(workpath):
    sources = set()

    def walk(node):
        if isinstance(node, (list, tuple)):
            if len(node) == 3 and all(isinstance(item, str) for item in node) and node[2] in TOC_TYPES:
                if node[1]:
                    sources.add(_absolute(node[1]))
                return
            for item in node:
                walk(item)

    for name in ('PKG-00.toc', 'PYZ-00.toc'):
        walk(ast.literal_eval((workpath / name).read_text(encoding='utf-8')))
    if not sources:
        raise ValueError('PyInstaller TOC lists no bundled files')
    return sources


def distribution_owners():
    owners = {}
    for dist in metadata.distributions():
        name = re.sub(r'[-_.]+', '-', dist.metadata['Name']).lower()
        dep = osv.Dependency('PyPI', name, dist.version, 'bundled')
        for file in dist.files or ():
            owners[_absolute(dist.locate_file(file))] = dep
    return owners


def _run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout.strip()


def debian_package(path, ecosystem):
    # dpkg may record /lib while the loader reports /usr/lib on merged-/usr.
    candidates = [path] + ([Path(str(path)[4:])] if str(path).startswith('/usr/lib/') else [])
    for candidate in candidates:
        try:
            owner = _run(['dpkg-query', '-S', str(candidate)]).split(': ', 1)[0]
        except subprocess.CalledProcessError:
            continue
        source, version = _run(['dpkg-query', '-W', '-f=${source:Package}\t${source:Version}', owner]).split('\t')
        return osv.Dependency(ecosystem, source, version, 'bundled')
    return None


def ubuntu_ecosystem():
    release = platform.freedesktop_os_release()
    if release.get('ID') != 'ubuntu':
        raise ValueError('Linux bundles are audited only on Ubuntu build hosts')
    return f"Ubuntu:{release['VERSION_ID']}" + (':LTS' if 'LTS' in release.get('VERSION', '') else '')


def classify(path, workpath, owners, runtime_prefixes, system=sys.platform, package=debian_package):
    """Return (category, dependency or None) for one bundled source file."""
    if path in owners:
        return 'python package', owners[path]
    if path.is_relative_to(workpath):
        return 'pyinstaller output', None
    if path.is_relative_to(ROOT) and not path.is_relative_to(ROOT / '.venv'):
        if path.is_relative_to(ROOT / 'resources') and path.name.lower().startswith(('adb', 'libusb', 'adbwinapi')):
            return 'platform-tools', None
        return 'project', None
    if any(path.is_relative_to(prefix) for prefix in runtime_prefixes):
        return 'python runtime', None
    if system.startswith('linux'):
        dep = package(path, ubuntu_ecosystem())
        if dep:
            return 'ubuntu package', dep
    elif system == 'win32' and path.is_relative_to(Path(os.environ.get('SystemRoot', r'C:\Windows')).resolve()):
        return 'windows system', None
    elif system == 'darwin' and path.parts[:3] in (('/', 'usr', 'lib'), ('/', 'System', 'Library')):
        return 'macos system', None
    raise ValueError(f'Bundled file has no audited source: {path}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workpath', type=Path, help='PyInstaller work directory with PKG-00.toc')
    args = parser.parse_args()
    workpath = _absolute(args.workpath)
    owners = distribution_owners()
    runtime = {_absolute(prefix) for prefix in (sys.base_prefix, sys.base_exec_prefix)}
    categories, deps = {}, set()
    for path in sorted(toc_sources(workpath)):
        category, dep = classify(path, workpath, owners, runtime)
        categories[category] = categories.get(category, 0) + 1
        if dep:
            deps.add(dep)
    # The bootloader is copied from PyInstaller itself, not listed in the TOC.
    deps.add(osv.Dependency('PyPI', 'pyinstaller', metadata.version('pyinstaller'), 'bundled'))
    findings = osv.audit_native(sorted(deps))
    for dep, identifier in findings:
        print(f'{dep.ecosystem} {dep.name} {dep.version}: https://osv.dev/vulnerability/{identifier}')
    print('Bundled files:', ', '.join(f'{name} {count}' for name, count in sorted(categories.items())))
    print('Audited sources:', ', '.join(f'{d.name} {d.version}' for d in sorted(deps)))
    print('Python runtime files are audited by verify_native_crypto.py and check_dependencies.py.')
    return 1 if findings else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(f'Bundle audit incomplete: {type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(2)
