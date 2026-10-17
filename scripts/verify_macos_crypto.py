"""Проверяет статический OpenSSL для переносимой сборки macOS Intel."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_native_crypto import PATCH_FLOORS  # noqa: E402

# Homebrew openssl@3 tracks the 3.5 LTS branch (supported until 2030-04-08).
LTS_BRANCH = (3, 5)


def openssl_dependencies(otool_output):
    return [line.strip().split()[0] for line in otool_output.splitlines()[1:]
            if re.search(r'(?:^|/)(?:libssl|libcrypto)(?:\.[^./ ]+)*\.dylib\s', line.strip())]


def verify_openssl_version(version_text):
    match = re.match(r'OpenSSL (\d+)\.(\d+)\.(\d+)(?:\s|$)', version_text)
    version = tuple(map(int, match.groups())) if match else ()
    floor = (*LTS_BRANCH, PATCH_FLOORS[LTS_BRANCH])
    if version[:2] != LTS_BRANCH or version < floor:
        raise RuntimeError(f'Static cryptography requires OpenSSL LTS {".".join(map(str, floor))} or a later 3.5 patch')


def main():
    if sys.platform != 'darwin' or os.environ.get('OPENSSL_STATIC') != '1':
        raise RuntimeError('Run this check on macOS with OPENSSL_STATIC=1')
    from cryptography.hazmat.bindings import _rust
    from cryptography.hazmat.backends.openssl.backend import backend

    output = subprocess.check_output(['otool', '-L', _rust.__file__], text=True)
    if openssl_dependencies(output):
        raise RuntimeError('cryptography still links to shared OpenSSL; discard the incompatible build cache')
    version_text = backend.openssl_version_text()
    verify_openssl_version(version_text)
    # Homebrew keeps the upstream licence in the installed keg. Include this
    # exact copy when redistributing its statically linked library.
    license_file = Path(os.environ['OPENSSL_DIR']) / 'LICENSE.txt'
    destination = Path('resources') / 'OPENSSL-LICENSE.txt'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(license_file, destination)
    print('cryptography: no shared libssl/libcrypto dependencies')
    print('cryptography:', version_text)
    print('OpenSSL licence copied for executable packaging')


if __name__ == '__main__':
    main()
