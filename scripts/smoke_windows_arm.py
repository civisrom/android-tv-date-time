"""Проверяет x64-сборку для Windows на Windows 11 ARM, где она работает в эмуляции x64."""
import argparse
from pathlib import Path
import platform
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_archive import unpack_verified  # noqa: E402

IMAGE_FILE_MACHINE_AMD64 = 0x8664
EXECUTABLE = 'AndroidTVTimeFixer.exe'


def pe_machine(path):
    with Path(path).open('rb') as image:
        header = image.read(4096)
    if header[:2] != b'MZ':
        raise ValueError(f'{path} is not a Windows executable')
    offset = struct.unpack_from('<I', header, 0x3C)[0]
    if header[offset:offset + 4] != b'PE\0\0':
        raise ValueError(f'{path} has no PE header')
    return struct.unpack_from('<H', header, offset + 4)[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path, help='AndroidTVTimeFixer-windows.zip with its .sha256 file')
    archive = parser.parse_args().archive.resolve(strict=True)
    if platform.machine().upper() != 'ARM64':
        raise RuntimeError(f'Expected a Windows ARM64 runner, got {platform.machine()}')
    with tempfile.TemporaryDirectory(prefix='windows-arm-') as directory:
        executable = unpack_verified(archive, directory) / EXECUTABLE
        if pe_machine(executable) != IMAGE_FILE_MACHINE_AMD64:
            raise RuntimeError('The Windows build is expected to be x64')
        subprocess.run([str(executable), '--version'], check=True, timeout=60)
        subprocess.run([sys.executable, str(Path(__file__).with_name('smoke_desktop.py')), str(executable)],
                       check=True, timeout=900)
    print('Windows 11 ARM: x64 build passed under emulation')


if __name__ == '__main__':
    main()
