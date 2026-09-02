"""Проверяет, уводит ли adb_env() настоящий adb в собственный каталог ключей.

Диагностика для CI: на Linux проверено, что adb 37.0.1 берёт каталог только из
HOME, а ANDROID_USER_HOME игнорирует. На Windows и macOS это не проверялось —
adb там может читать профиль через системный API, и тогда изоляция ключей не
сработает. Скрипт запускается вручную через workflow adb-home-probe.yml.

Использование: python scripts/probe_adb_home.py <путь к adb>
"""

import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'src'))
try:
    from android_time_fixer import adb_env
except ImportError:  # ветка без поддержки беспроводной отладки
    print("В этой ветке нет adb_env(): запускайте пробу на ветке, где реализована "
          "изоляция ADB (gh workflow run adb-home-probe.yml --ref dev).")
    sys.exit(1)

PROBE_SERVER_PORT = 5199
ADB_ARTEFACTS = ('adbkey', 'adbkey.pub', f'adb.{PROBE_SERVER_PORT}')


def snapshot(directory: Path) -> set:
    """Имена файлов в каталоге; отсутствующий каталог — пустое множество."""
    try:
        return {item.name for item in directory.iterdir()}
    except OSError:
        return set()


#: Наборы переменных-кандидатов: чем ещё можно попробовать увести adb в свой
#: каталог, если основной способ (HOME/USERPROFILE) на этой ОС не сработал.
CANDIDATES = (
    ('HOME + USERPROFILE', ('HOME', 'USERPROFILE')),
    ('ANDROID_USER_HOME', ('ANDROID_USER_HOME',)),
    ('ANDROID_SDK_HOME', ('ANDROID_SDK_HOME',)),
    ('USERPROFILE', ('USERPROFILE',)),
    ('HOME', ('HOME',)),
    ('HOMEDRIVE + HOMEPATH', ('HOMEDRIVE', 'HOMEPATH')),
)


def explore(adb: str, real_android: Path) -> int:
    """Перебирает наборы переменных и печатает, какой из них уводит adb."""
    import os

    print(f"ОС: {sys.platform}. Ищем переменные, которыми adb можно увести в свой каталог.\n")
    winners = []
    for index, (label, names) in enumerate(CANDIDATES):
        port = PROBE_SERVER_PORT + 1 + index
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir) / 'adb'
            (home / '.android').mkdir(parents=True)
            env = os.environ.copy()
            env['ANDROID_ADB_SERVER_PORT'] = str(port)
            for name in names:
                if name == 'HOMEDRIVE':
                    env[name] = str(home.drive or 'C:')
                elif name == 'HOMEPATH':
                    env[name] = str(home)[len(home.drive):] if home.drive else str(home)
                else:
                    env[name] = str(home)

            before = snapshot(real_android)
            subprocess.run([adb, 'start-server'], env=env,
                           capture_output=True, text=True, timeout=120)
            subprocess.run([adb, 'devices'], env=env,
                           capture_output=True, text=True, timeout=120)
            ours = snapshot(home / '.android') | snapshot(home)
            leaked = snapshot(real_android) - before
            subprocess.run([adb, 'kill-server'], env=env,
                           capture_output=True, text=True, timeout=60)

            marker = f'adb.{port}'
            works = marker in ours or 'adbkey' in ours
            if works:
                winners.append(label)
            print(f"  {label:22} -> {'УВОДИТ' if works else 'не уводит':10} "
                  f"| у нас: {sorted(ours) or 'ничего'} | утекло: {sorted(leaked) or 'ничего'}")

    print()
    if winners:
        print("Сработали наборы:", ", ".join(winners))
        return 0
    print("Ни один набор переменных не уводит adb с этой ОС.")
    return 1


def main() -> int:
    adb = str(Path(sys.argv[1]).resolve())
    real_android = Path.home() / '.android'

    if '--explore' in sys.argv:
        return explore(adb, real_android)

    with tempfile.TemporaryDirectory() as temp_dir:
        adb_home = Path(temp_dir) / 'adb'
        (adb_home / '.android').mkdir(parents=True)
        env = adb_env(adb_home, PROBE_SERVER_PORT)

        print(f"ОС                : {sys.platform}")
        print(f"adb               : {adb}")
        print(f"наш HOME          : {env.get('HOME')}")
        print(f"наш USERPROFILE   : {env.get('USERPROFILE')}")
        print(f"наш порт сервера  : {env.get('ANDROID_ADB_SERVER_PORT')}")
        print(f"настоящий ~/.android: {real_android}")

        before = snapshot(real_android)

        for args in (['start-server'], ['devices']):
            result = subprocess.run(
                [adb] + args, env=env, capture_output=True, text=True, timeout=120
            )
            print(f"  adb {' '.join(args):13} -> rc={result.returncode} "
                  f"{(result.stdout or '').strip()[:60]!r}")

        ours = snapshot(adb_home / '.android')
        after = snapshot(real_android)
        leaked = sorted((after - before) & set(ADB_ARTEFACTS))

        subprocess.run([adb, 'kill-server'], env=env,
                       capture_output=True, text=True, timeout=60)

        print()
        print(f"появилось в НАШЕМ каталоге     : {sorted(ours) or 'ничего'}")
        print(f"появилось в настоящем ~/.android: {leaked or 'ничего'}")

        isolated = bool(ours & set(ADB_ARTEFACTS)) and not leaked
        if isolated:
            print("\nВЫВОД: изоляция ключей РАБОТАЕТ — adb пишет только в наш каталог.")
            return 0

        print("\nВЫВОД: изоляция ключей НЕ РАБОТАЕТ на этой ОС.")
        print("Изоляция порта ADB-сервера от этого не зависит и продолжает работать;")
        print("ключи останутся в системном каталоге пользователя.")
        # Ненулевой код, чтобы результат был виден в статусе задания
        return 1


if __name__ == '__main__':
    sys.exit(main())
