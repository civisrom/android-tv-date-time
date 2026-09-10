"""Проверка первого запуска собранного приложения на одноразовом CI runner."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('executable', type=Path)
    executable = parser.parse_args().executable.resolve(strict=True)
    # Обычный запуск приложения может мигрировать ADB identity из профиля ОС.
    # Здесь допустим только временный пользователь GitHub runner.
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Run this smoke test only on a disposable GitHub runner')
    cases = (
        ('language-eof', '', ('Выберите язык',)),
        ('english-menu-eof', '1\n\n', ('Main Menu',)),
        ('russian-exit', '2\n\n0\n\n', ('Главное меню',)),
        ('english-terminal-help', '1\n\n10\nhelp\nadb --help\nexit\n0\n\n',
         ('ADB reference for the desktop application', 'adb bugreport bugreport.zip',
          'Android Debug Bridge version')),
        ('russian-terminal-help', '2\n\n10\nhelp\nadb --help\nexit\n0\n\n',
         ('Справочник ADB для десктопной программы', 'adb bugreport bugreport.zip',
          'Android Debug Bridge version')),
    )
    for name, answers, expected in cases:
        with tempfile.TemporaryDirectory(prefix='desktop-smoke-') as directory:
            root = Path(directory)
            # Данные переносимого приложения находятся рядом с executable.
            binary = root / executable.name
            shutil.copy2(executable, binary)
            output = root / 'console.txt'
            with output.open('wb') as stream:
                result = subprocess.run([str(binary)], input=answers.encode('utf-8'),
                                        stdout=stream, stderr=subprocess.STDOUT, cwd=root, timeout=45)
            if output.stat().st_size > 256 * 1024:
                raise AssertionError(f'{name}: excessive console output')
            text = output.read_text(encoding='utf-8', errors='replace')
            if result.returncode != 0 or any(marker not in text for marker in expected) or 'Traceback' in text:
                raise AssertionError(f'{name}: exit={result.returncode}, output={text[-3000:]}')
            print(f'{name}: passed')


if __name__ == '__main__':
    main()
