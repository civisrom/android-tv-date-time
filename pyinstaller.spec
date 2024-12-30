# -*- mode: python ; coding: utf-8 -*-
import sys
import os
from PyInstaller.utils.hooks import collect_all

# Определяем базовый путь проекта
BASEPATH = os.path.dirname(os.path.abspath('__file__'))

# Определяем пути к ресурсам
HOOKS_PATH = os.path.join(BASEPATH, 'scripts', 'hooks')
SRC_PATH = os.path.join(BASEPATH, 'src')

# Добавляем src в PYTHONPATH
sys.path.insert(0, SRC_PATH)

datas = []
binaries = []
hiddenimports = [
    'logging',
    'urllib',
    'urllib.parse',
    'urllib.error',
    'urllib.request',
    'urllib.response',
    'pathlib',
    '_collections_abc',
    'encodings.idna'
]

# Collect all necessary packages
packages = [
    'pyperclip', 
    'colorama', 
    'platformdirs', 
    'packaging', 
    'typing_extensions',
    'cryptography', 
    'rsa', 
    'aiofiles', 
    'async_timeout', 
    'asyncio', 
    'socket',
    'subprocess', 
    'threading', 
    'adb_shell.adb_device', 
    'adb_shell.auth.sign_pythonrsa',
    'ntplib', 
    'psutil'
]

# Only collect from actual packages to avoid warnings
for package in packages:
    try:
        __import__(package)
        tmp_ret = collect_all(package)
        if tmp_ret[0]:
            datas.extend(tmp_ret[0])
        if tmp_ret[1]:
            binaries.extend(tmp_ret[1])
        if tmp_ret[2]:
            hiddenimports.extend(tmp_ret[2])
    except ImportError:
        continue

# Определяем платформо-зависимые настройки
if sys.platform == 'win32':
    runtime_hooks = [os.path.join(HOOKS_PATH, 'win_hook.py')]
    platform_data = [
        ('resources/adb.exe', 'resources/adb.exe', 'DATA'),
        ('resources/AdbWinApi.dll', 'resources/AdbWinApi.dll', 'DATA'),
        ('resources/AdbWinUsbApi.dll', 'resources/AdbWinUsbApi.dll', 'DATA')
    ]
    # Добавляем системные DLL для Windows
    python_dlls = [
        f'python{sys.version_info.major}{sys.version_info.minor}.dll',
        'vcruntime140.dll',
        'msvcp140.dll',
    ]
    for dll in python_dlls:
        dll_path = os.path.join(sys.prefix, dll)
        if os.path.exists(dll_path):
            binaries.append((dll, dll_path, 'BINARY'))
            
    # Добавляем необходимые Windows API DLL
    system32_path = os.path.join(os.environ.get('SystemRoot', 'C:\\Windows'), 'System32')
    required_dlls = [
        'api-ms-win-core-path-l1-1-0.dll',
        'api-ms-win-core-file-l1-1-0.dll',
        'api-ms-win-core-file-l1-2-0.dll',
        'api-ms-win-core-file-l2-1-0.dll',
    ]
    for dll in required_dlls:
        dll_path = os.path.join(system32_path, dll)
        if os.path.exists(dll_path):
            binaries.append((dll, dll_path, 'BINARY'))
elif sys.platform == 'darwin':
    runtime_hooks = [os.path.join(HOOKS_PATH, 'macos_hook.py')]
    platform_data = [('resources/adb', 'resources/adb', 'DATA')]
else:  # linux
    runtime_hooks = [os.path.join(HOOKS_PATH, 'linux_hook.py')]
    platform_data = [('resources/adb', 'resources/adb', 'DATA')]

# Добавляем платформо-зависимые данные
datas.extend(platform_data)

a = Analysis(
    [os.path.join(SRC_PATH, 'android_time_fixer.py')],
    pathex=[BASEPATH],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=runtime_hooks,
    excludes=[
        'tkinter',
        'unittest',
        'pdb',
        'difflib',
        'doctest',
        'xml',
        'pydoc',
        'test',
        '_pytest',
        'pytest',
        'pip',
        'pkg_resources',
        'email',
        'html',
        'http',
        'xmlrpc'
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False
)

pyz = PYZ(a.pure, a.zipped_data, cipher=None)

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
    upx=True,
    upx_exclude=['vcruntime140.dll', 'python*.dll', 'api-ms-*.dll'],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)