"""Проверяет статический OpenSSL для переносимой сборки macOS Intel."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def openssl_dependencies(otool_output):
    return [line.strip().split()[0] for line in otool_output.splitlines()[1:]
            if re.search(r'(?:^|/)(?:libssl|libcrypto)(?:\.[^/ ]+)*\.dylib\s', line.strip())]


def main():
    if sys.platform != 'darwin' or os.environ.get('OPENSSL_STATIC') != '1':
        raise RuntimeError('Run this check on macOS with OPENSSL_STATIC=1')
    from cryptography.hazmat.bindings import _rust
    from cryptography.hazmat.backends.openssl.backend import backend

    output = subprocess.check_output(['otool', '-L', _rust.__file__], text=True)
    if openssl_dependencies(output):
        raise RuntimeError('cryptography still links to shared OpenSSL; discard the incompatible build cache')
    # Homebrew keeps the upstream licence in the installed keg. Include this
    # exact copy when redistributing its statically linked library.
    license_file = Path(os.environ['OPENSSL_DIR']) / 'LICENSE.txt'
    destination = Path('resources') / 'OPENSSL-LICENSE.txt'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(license_file, destination)
    print('cryptography: no shared libssl/libcrypto dependencies')
    print('cryptography:', backend.openssl_version_text())
    print('OpenSSL licence copied for executable packaging')


if __name__ == '__main__':
    main()
