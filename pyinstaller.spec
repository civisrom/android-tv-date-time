# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec file for AndroidTVTimeFixer
Оптимизированная конфигурация для кросс-платформенной сборки
"""
import sys
import os
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

# Определяем пути проекта
SRC_PATH = SPEC_DIR / 'src'
HOOKS_PATH = SPEC_DIR / 'scripts' / 'hooks'

# Добавляем src в PYTHONPATH
sys.path.insert(0, str(SRC_PATH))

# Инициализация коллекций
datas = []
binaries = []
hiddenimports = [
    # Стандартная библиотека
    'logging',
    'urllib.parse',
    'urllib.error', 
    'urllib.request',
    'pathlib',
    'concurrent.futures',
    # ADB специфичные
    'adb_shell.adb_device',
    'adb_shell.auth.sign_pythonrsa',
    'adb_shell.auth.keygen',
]

# Пакеты для автоматического сбора зависимостей
PACKAGES_TO_COLLECT = [
    'pyperclip',
    'colorama', 
    'platformdirs',
    'packaging',
    'typing_extensions',
    'cryptography',
    'rsa',
    'ntplib',
]

# Собираем зависимости пакетов
for package_name in PACKAGES_TO_COLLECT:
    try:
        pkg_datas, pkg_binaries, pkg_hiddenimports = collect_all(package_name)
        datas.extend(pkg_datas)
        binaries.extend(pkg_binaries)
        hiddenimports.extend(pkg_hiddenimports)
    except ImportError:
        print(f"Warning: Package {package_name} not found, skipping")
    except Exception as e:
        print(f"Warning: Error collecting {package_name}: {e}")

# Платформо-зависимые настройки
if sys.platform == 'win32':
    # Windows
    runtime_hooks = [str(HOOKS_PATH / 'win_hook.py')]
    
    # ADB ресурсы для Windows
    platform_datas = [
        (str(SPEC_DIR / 'resources' / 'adb.exe'), 'resources'),
        (str(SPEC_DIR / 'resources' / 'AdbWinApi.dll'), 'resources'),
        (str(SPEC_DIR / 'resources' / 'AdbWinUsbApi.dll'), 'resources'),
    ]
    
    # Python DLL для Windows
    python_version = f"{sys.version_info.major}{sys.version_info.minor}"
    system_dlls = [
        f"python{python_version}.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",  # Для новых версий Python
        "msvcp140.dll",
    ]
    
    for dll_name in system_dlls:
        dll_path = Path(sys.prefix) / dll_name
        if dll_path.exists():
            binaries.append((str(dll_path), '.'))
    
    # psutil для Windows
    try:
        psutil_data, psutil_bin, psutil_hidden = collect_all('psutil')
        datas.extend(psutil_data)
        binaries.extend(psutil_bin)
        hiddenimports.extend(psutil_hidden)
        
        # WMI для Windows
        wmi_data, wmi_bin, wmi_hidden = collect_all('wmi')
        datas.extend(wmi_data)
        binaries.extend(wmi_bin)
        hiddenimports.extend(wmi_hidden)
    except ImportError:
        pass

elif sys.platform == 'darwin':
    # macOS
    runtime_hooks = [str(HOOKS_PATH / 'macos_hook.py')]
    platform_datas = [(str(SPEC_DIR / 'resources' / 'adb'), 'resources')]
    
    # psutil для macOS
    try:
        psutil_data, psutil_bin, psutil_hidden = collect_all('psutil')
        datas.extend(psutil_data)
        binaries.extend(psutil_bin)
        hiddenimports.extend(psutil_hidden)
    except ImportError:
        pass

else:
    # Linux
    runtime_hooks = [str(HOOKS_PATH / 'linux_hook.py')]
    platform_datas = [(str(SPEC_DIR / 'resources' / 'adb'), 'resources')]

# Добавляем базовый хук для всех платформ
runtime_hooks.insert(0, str(HOOKS_PATH / 'base_hook.py'))

# Добавляем платформо-зависимые данные
datas.extend(platform_datas)

# Модули для исключения (оптимизация размера)
excludes = [
    # GUI фреймворки
    'tkinter', 'PyQt5', 'PyQt6', 'PySide6', 'wx',
    # Тестирование
    'unittest', 'pytest', '_pytest', 'test',
    # Документация
    'pdb', 'pydoc', 'doctest',
    # Веб
    'http', 'html', 'xmlrpc', 'email',
    # Научные библиотеки
    'numpy', 'pandas', 'scipy', 'matplotlib',
    # Разработка
    'pip', 'setuptools', 'wheel', 'pkg_resources',
]

# PyInstaller Analysis
a = Analysis(
    [str(SRC_PATH / 'android_time_fixer.py')],
    pathex=[str(SPEC_DIR)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=runtime_hooks,
    excludes=excludes,
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False,
)

# PYZ Archive
pyz = PYZ(a.pure, a.zipped_data, cipher=None)

# EXE Build
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='AndroidTVTimeFixer',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,  # Сжатие исполняемого файла
    upx_exclude=[
        # Исключаем из UPX компрессии критичные библиотеки
        'vcruntime*.dll',
        'python*.dll',
        'api-ms-*.dll',
    ],
    runtime_tmpdir=None,
    console=True,  # Консольное приложение
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
