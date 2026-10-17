"""Собирает adb для Linux ARM64 из закреплённых исходников android-tools.

Google публикует platform-tools для Linux только под x86-64. Для ARM64 adb
собирается из выпуска nmeum/android-tools (AOSP adb со сборкой на CMake) на
Ubuntu 22.04, как и остальная Linux-сборка. Библиотеки, с которыми adb связан
динамически, PyInstaller кладёт в исполняемый файл, а audit_bundle.py
проверяет их по OSV как пакеты Ubuntu; статический libusb проверяется по OSV
как исходники GitHub.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
# https://github.com/nmeum/android-tools/releases/tag/37.0.0p1
ANDROID_TOOLS_VERSION = '37.0.0p1'
ANDROID_TOOLS_SHA256 = '2c6de1abe16b211c7d9f59276217e14fd9e0e18b20e331d3c84358f5dffd576e'
ANDROID_TOOLS_URL = ('https://github.com/nmeum/android-tools/releases/download/'
                     f'{ANDROID_TOOLS_VERSION}/android-tools-{ANDROID_TOOLS_VERSION}.tar.xz')
# Снимок libusb из AOSP в android-tools не содержит исправлений 1.0.30
# (CVE-2026-23679, CVE-2026-47104), поэтому его исходники заменяются выпуском.
# https://github.com/libusb/libusb/releases/tag/v1.0.30
LIBUSB_VERSION = '1.0.30'
LIBUSB_SHA256 = 'fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf'
LIBUSB_URL = (f'https://github.com/libusb/libusb/releases/download/v{LIBUSB_VERSION}/'
              f'libusb-{LIBUSB_VERSION}.tar.bz2')
LIBUSB_GIT = 'https://github.com/libusb/libusb'
# Строка версии, которую adb из этих исходников печатает и хранит в файле.
SOURCE_MARKER = f'{ANDROID_TOOLS_VERSION}-android-tools'
APT_PACKAGES = ('cmake', 'ninja-build', 'pkg-config', 'gcc-12', 'g++-12', 'patchelf',
                'libprotobuf-dev', 'protobuf-compiler', 'libbrotli-dev', 'libzstd-dev',
                'liblz4-dev', 'libpcre2-dev', 'libgtest-dev')
# adb лежит в resources/, а PyInstaller кладёт его библиотеки в корень сборки.
# DT_RPATH, в отличие от RUNPATH, действует и на зависимости этих библиотек.
RPATH = '$ORIGIN/..'


def download(url, path, expected_sha256):
    deadline = time.monotonic() + 300
    size = 0
    with urllib.request.urlopen(url, timeout=60) as response, path.open('wb') as output:
        while chunk := response.read(256 * 1024):
            size += len(chunk)
            if size > 64 * 1024 * 1024 or time.monotonic() > deadline:
                raise RuntimeError(f'{path.name} download exceeded its size or time limit')
            output.write(chunk)
    with path.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != expected_sha256:
            raise RuntimeError(f'{path.name} SHA-256 mismatch')


def extract(archive, destination):
    with tarfile.open(archive) as source:
        source.extractall(destination, filter='data')


def replace_libusb(android_tools, libusb):
    vendored = android_tools / 'vendor/libusb/libusb'
    if not (vendored / 'core.c').is_file() or not (libusb / 'libusb/core.c').is_file():
        raise RuntimeError('Unexpected libusb source layout')
    shutil.rmtree(vendored)
    shutil.copytree(libusb / 'libusb', vendored)
    shutil.copy2(libusb / 'COPYING', android_tools / 'vendor/libusb/COPYING')


def run(command, **kwargs):
    subprocess.run(command, check=True, timeout=3600, **kwargs)


def verify(adb):
    version = subprocess.run([str(adb), 'version'], check=True, capture_output=True, text=True,
                             timeout=30).stdout
    rpath = subprocess.run(['patchelf', '--print-rpath', str(adb)], check=True, capture_output=True,
                           text=True, timeout=30).stdout.strip()
    if f'Version {SOURCE_MARKER}' not in version or rpath != RPATH:
        raise RuntimeError(f'Unexpected adb build: {version!r}, rpath {rpath!r}')
    return version.splitlines()[1]


def build(output):
    with tempfile.TemporaryDirectory(prefix='android-tools-') as directory:
        work = Path(directory)
        sources = work / 'sources'
        download(ANDROID_TOOLS_URL, work / 'android-tools.tar.xz', ANDROID_TOOLS_SHA256)
        download(LIBUSB_URL, work / 'libusb.tar.bz2', LIBUSB_SHA256)
        extract(work / 'android-tools.tar.xz', sources)
        extract(work / 'libusb.tar.bz2', sources)
        android_tools = sources / f'android-tools-{ANDROID_TOOLS_VERSION}'
        replace_libusb(android_tools, sources / f'libusb-{LIBUSB_VERSION}')
        # protoc 3.12 из Ubuntu 22.04 принимает поля proto3 optional только с флагом.
        system_protoc = shutil.which('protoc')
        if not system_protoc:
            raise RuntimeError('protoc is not installed')
        protoc = work / 'protoc'
        protoc.write_text(f'#!/bin/sh\nexec {system_protoc} --experimental_allow_proto3_optional "$@"\n',
                          encoding='utf-8')
        protoc.chmod(0o755)
        build_dir = work / 'build'
        run(['cmake', '-S', str(android_tools), '-B', str(build_dir), '-G', 'Ninja',
             '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_C_COMPILER=gcc-12', '-DCMAKE_CXX_COMPILER=g++-12',
             '-DANDROID_TOOLS_USE_BUNDLED_FMT=ON', '-DANDROID_TOOLS_USE_BUNDLED_LIBUSB=ON',
             # Без mDNS-бэкенда adb программа ищет устройства через zeroconf.
             '-DANDROID_TOOLS_ADB_ENABLE_MDNS=OFF', f'-DProtobuf_PROTOC_EXECUTABLE={protoc}'])
        run(['cmake', '--build', str(build_dir), '--target', 'adb', '-j', str(os.cpu_count() or 2)])
        adb = build_dir / 'vendor/adb'
        run(['strip', '--strip-unneeded', str(adb)])
        run(['patchelf', '--force-rpath', '--set-rpath', RPATH, str(adb)])
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(adb, output)
    output.chmod(0o755)
    return verify(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--print-apt-packages', action='store_true',
                        help='print the Ubuntu packages needed to build adb and exit')
    parser.add_argument('--output', type=Path, default=ROOT / 'resources' / 'adb')
    args = parser.parse_args()
    if args.print_apt_packages:
        print(' '.join(APT_PACKAGES))
        return
    if not sys.platform.startswith('linux'):
        raise RuntimeError('adb is built from source only for Linux')
    print(f'Built adb {build(args.output.resolve())} with libusb {LIBUSB_VERSION}: {args.output}')


if __name__ == '__main__':
    main()
