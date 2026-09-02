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


def main() -> int:
    adb = str(Path(sys.argv[1]).resolve())
    real_android = Path.home() / '.android'

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
