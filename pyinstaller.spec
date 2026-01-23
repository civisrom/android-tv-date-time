# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller .spec для AndroidTVTimeFixer
Оптимизировано для onefile-сборки на Windows / Linux / macOS
"""
import sys
import os
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

# Для Windows иногда помогает
if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")

# Пути (SPECPATH — это директория, где лежит этот .spec)
SPEC_DIR = Path(SPECPATH)
SRC_PATH = SPEC_DIR / 'src'
HOOKS_PATH = SPEC_DIR / 'scripts' / 'hooks'

# Добавляем src в sys.path
sys.path.insert(0, str(SRC_PATH))

# Список hiddenimports
hiddenimports = [
    'logging',
    'urllib.parse',
    'urllib.error',
    'urllib.request',
    'pathlib',
    'concurrent.futures',
    # ADB
    'adb_shell.adb_device',
    'adb_shell.auth.sign_pythonrsa',
    'adb_shell.auth.keygen',
]

# Пакеты, из которых собираем всё автоматически
PACKAGES_TO_COLLECT = [
    'pyperclip',
    'colorama',
    'platformdirs',
    'packaging',
    'typing_extensions',
    'cryptography',
    'rsa',
    'ntplib',
    'psutil',
]

datas = [
base_hook_path = HOOKS_PATH / 'base_hook.py'
if base_hook_path.exists():
    datas.append((str(base_hook_path), 'scripts/hooks'))
    print(f" ✓ Added base_hook.py as data")
]
binaries = []

print("Собираем зависимости пакетов...")
for package_name in PACKAGES_TO_COLLECT:
    try:
        pkg_datas, pkg_binaries, pkg_hidden = collect_all(package_name)
        datas.extend(pkg_datas)
        binaries.extend(pkg_binaries)
        hiddenimports.extend(pkg_hidden)
        print(f" ✓ {package_name}")
    except Exception as e:
        print(f" ⚠ Не удалось собрать {package_name}: {e}")

# Добавляем свои файлы из src, если они есть
for file_name in ['locales.py', 'constants.py', 'logging_config.py']:
    file_path = SRC_PATH / file_name
    if file_path.exists():
        datas.append((str(file_path), 'src'))
        print(f" ✓ Добавлен {file_name}")
    else:
        print(f"   (не найден) {file_name}")

# Runtime hooks (если есть)
runtime_hooks = []
hook_file = None

if sys.platform == 'win32':
    hook_file = HOOKS_PATH / 'win_hook.py'
elif sys.platform == 'darwin':
    hook_file = HOOKS_PATH / 'macos_hook.py'
else:  # linux и другие
    hook_file = HOOKS_PATH / 'linux_hook.py'

if hook_file and hook_file.exists():
    runtime_hooks.append(str(hook_file))
    print(f" ✓ Используется runtime hook: {hook_file.name}")

# Что исключаем (чтобы уменьшить размер)
EXCLUDES = [
    'setuptools', 'pip', 'wheel', 'poetry',
    'pytest', 'unittest', 'nose', 'hypothesis',
    'sphinx', 'docutils',
    'tkinter', 'PyQt5', 'PyQt6', 'PySide2', 'PySide6', 'wx',
    'numpy', 'pandas', 'scipy', 'matplotlib',
    'flask', 'django', 'tornado', 'aiohttp',
]

# Настройки в зависимости от платформы
if sys.platform == 'win32':
    exe_name = 'AndroidTVTimeFixer.exe'
    console = True          # консольное приложение → True
    icon = None             # 'path/to/icon.ico' если есть
elif sys.platform == 'darwin':
    exe_name = 'AndroidTVTimeFixer'
    console = False         # на macOS обычно без консоли
    icon = None             # 'path/to/icon.icns' если есть
else:  # Linux и другие
    exe_name = 'AndroidTVTimeFixer'
    console = True
    icon = None

print("\nКонфигурация:")
print(f"  Платформа:     {sys.platform}")
print(f"  Имя:           {exe_name}")
print(f"  Консоль:       {console}")
print(f"  Hidden imports: {len(hiddenimports)}")
print(f"  Datas:         {len(datas)}")
print(f"  Binaries:      {len(binaries)}")
print(f"  Runtime hooks: {len(runtime_hooks)}")

# Анализ
a = Analysis(
    [str(SRC_PATH / 'android_time_fixer.py')],
    pathex=[str(SRC_PATH)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=runtime_hooks,
    excludes=EXCLUDES,
    noarchive=False,
    optimize=1,          # 0 = без оптимизации, 1 = норм, 2 = агрессивно (иногда ломается)
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    name=exe_name,
    debug=False,
    bootloader_ignore_signals=False,
    strip=sys.platform != 'win32',   # strip полезен на linux/macos
    upx=True,                        # можно поставить False для отладки
    console=console,
    disable_windowed_traceback=False,
    argv_emulation=False,            # только macOS
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

print("\n✓ .spec файл успешно загружен")
