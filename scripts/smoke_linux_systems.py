"""Запускает Linux-сборку в чистых контейнерах распространённых дистрибутивов.

В контейнерах нет ни Python, ни библиотек сборочного раннера, а сеть отключена:
исполняемый файл и встроенный adb должны обойтись тем, что лежит в сборке.
"""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_archive import unpack_verified  # noqa: E402

# Мультиархитектурные образы, закреплённые по digest (amd64 и arm64).
IMAGES = {
    'Ubuntu 22.04': 'ubuntu:22.04@sha256:0a9887931b0e38af2e3487ed39e29436b0535c4f3f52e57745baaf79d1898956',
    'Debian 12 (Raspberry Pi OS Bookworm)':
        'debian:12@sha256:2c037a04925515fdd6ea85ea14a682d0e79931f5e9f5d07b6dbfc6ba12f9e858',
    'Ubuntu 24.04': 'ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac',
}
EXECUTABLE = 'AndroidTVTimeFixer'
# Английский язык, терминал, adb version и adb devices (запускает сервер adb),
# выход из терминала и программы.
ANSWERS = '1\n\n10\nadb version\nadb devices\nexit\n0\n\n'
EXPECTED = ('Main Menu', 'Android Debug Bridge version', 'List of devices attached')


def check(system, returncode, output):
    problems = [marker for marker in ('error while loading shared libraries', 'Traceback') if marker in output]
    if returncode != 0 or problems or any(marker not in output for marker in EXPECTED):
        raise AssertionError(f'{system}: exit={returncode}, problems={problems}, output={output[-3000:]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path, help='AndroidTVTimeFixer-linux*.zip with its .sha256 file')
    archive = parser.parse_args().archive.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix='linux-systems-') as directory:
        dist = unpack_verified(archive, directory)
        (dist / EXECUTABLE).chmod(0o755)
        for system, image in IMAGES.items():
            # Данные переносимой программы пишутся рядом с ней, поэтому копия
            # запускается из каталога контейнера, а не из подключённого только для чтения.
            script = (f'mkdir /tmp/app && cp /dist/{EXECUTABLE} /tmp/app/ && cd /tmp/app '
                      f'&& ./{EXECUTABLE} --version && ./{EXECUTABLE}')
            result = subprocess.run(['docker', 'run', '--rm', '-i', '--network', 'none',
                                     '-v', f'{dist}:/dist:ro', image, 'sh', '-c', script],
                                    input=ANSWERS, capture_output=True, text=True, errors='replace',
                                    timeout=300)
            check(system, result.returncode, result.stdout + result.stderr)
            version = next((line for line in result.stdout.splitlines() if line.startswith('Version ')), '')
            print(f'{system}: passed ({version.strip()})')


if __name__ == '__main__':
    main()
