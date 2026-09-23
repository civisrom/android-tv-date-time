"""Проверка первого запуска собранного приложения на одноразовом CI runner."""
import argparse
import json
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
    time_answers = ('\n\n7\ns\nb\nm\nv\np\n1\n0\np\n2\np\n3\np\n4\nd\n\nd\nreport.json\n8\n0\n\n')
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
        ('english-time-tools', '1' + time_answers,
         ('Save time snapshot', 'No device connected', 'No saved device profiles.', 'Diagnostic report saved')),
        ('russian-time-tools', '2' + time_answers,
         ('Сохранить снимок времени', 'Не подключено ни к одному устройству',
          'Сохранённых профилей устройств нет.', 'Диагностический отчёт сохранён')),
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
            if name.endswith('-time-tools'):
                report = json.loads((root / 'report.json').read_text(encoding='utf-8'))
                allowed = {'schema_version', 'app_version', 'platform', 'exported_at', 'transport_selected',
                           'android_api', 'settings_read', 'read_failure', 'automatic_time', 'automatic_timezone',
                           'ntp_configuration', 'clock_status', 'clock_difference_seconds',
                           'clock_uncertainty_seconds', 'system_clock_source'}
                if (set(report) != allowed or report['transport_selected'] is not False
                        or report['settings_read'] is not False or report['clock_status'] != 'DEVICE_UNAVAILABLE'
                        or report['system_clock_source'] != 'unconfirmed'
                        or text.count(expected[1]) < 5):
                    raise AssertionError(f'{name}: invalid offline time-tool behavior')
            print(f'{name}: passed')


if __name__ == '__main__':
    main()
