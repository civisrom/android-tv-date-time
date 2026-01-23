# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec file for AndroidTVTimeFixer
Optimized configuration for cross-platform builds
"""
import sys
import os
from pathlib import Path
from PyInstaller.utils.hooks import collect_all

if sys.platform == "win32":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")

# Determine project paths using SPECPATH (PyInstaller built-in variable)
# SPECPATH is always available and points to the directory containing this spec file
SPEC_DIR = Path(SPECPATH)
SRC_PATH = SPEC_DIR / 'src'
HOOKS_PATH = SPEC_DIR / 'scripts' / 'hooks'

# Add src to PYTHONPATH
sys.path.insert(0, str(SRC_PATH))

# Initialize collections
datas = []
binaries = []
hiddenimports = [
    # Standard library
    'logging',
    'urllib.parse',
    'urllib.error', 
    'urllib.request',
    'pathlib',
    'concurrent.futures',
    # ADB specific
    'adb_shell.adb_device',
    'adb_shell.auth.sign_pythonrsa',
    'adb_shell.auth.keygen',
]

# Packages to collect automatically
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

# Collect all data files and hidden imports from packages
print("Collecting package dependencies...")
for package_name in PACKAGES_TO_COLLECT:
    try:
        pkg_datas, pkg_binaries, pkg_hiddenimports = collect_all(package_name)
        datas += pkg_datas
        binaries += pkg_binaries
        hiddenimports += pkg_hiddenimports
        print(f"  ✓ {package_name}")
    except Exception as e:
        print(f"  ⚠ Warning: Could not collect {package_name}: {e}")

# Add locales file
locales_file = SRC_PATH / 'locales.py'
if locales_file.exists():
    datas.append((str(locales_file), 'src'))
    print(f"  ✓ Added locales.py")
else:
    print(f"  ⚠ Warning: locales.py not found at {locales_file}")

# Add constants and logging config if they exist
for module_name in ['constants.py', 'logging_config.py']:
    module_file = SRC_PATH / module_name
    if module_file.exists():
        datas.append((str(module_file), 'src'))
        print(f"  ✓ Added {module_name}")

# Platform-specific runtime hooks
runtime_hooks = []
if sys.platform == 'win32':
    hook_file = HOOKS_PATH / 'win_hook.py'
    if hook_file.exists():
        runtime_hooks.append(str(hook_file))
        print(f"  ✓ Using Windows runtime hook")
elif sys.platform == 'darwin':
    hook_file = HOOKS_PATH / 'macos_hook.py'
    if hook_file.exists():
        runtime_hooks.append(str(hook_file))
        print(f"  ✓ Using macOS runtime hook")
else:  # Linux
    hook_file = HOOKS_PATH / 'linux_hook.py'
    if hook_file.exists():
        runtime_hooks.append(str(hook_file))
        print(f"  ✓ Using Linux runtime hook")

# Optimized excludes list
EXCLUDES = [
    # Development tools
    'setuptools',
    'pip',
    'wheel',
    'poetry',
    # Testing frameworks
    'pytest',
    'unittest',
    'nose',
    'hypothesis',
    # Documentation
    'sphinx',
    'docutils',
    # GUI frameworks (not needed)
    'tkinter',
    'PyQt5',
    'PyQt6',
    'PySide2',
    'PySide6',
    'wx',
    # Scientific libraries (not needed)
    'numpy',
    'pandas',
    'scipy',
    'matplotlib',
    # Web frameworks (not needed)
    'flask',
    'django',
    'tornado',
    'aiohttp',
]

print(f"\nConfiguration summary:")
print(f"  Source path: {SRC_PATH}")
print(f"  Hooks path: {HOOKS_PATH}")
print(f"  Runtime hooks: {len(runtime_hooks)}")
print(f"  Hidden imports: {len(hiddenimports)}")
print(f"  Data files: {len(datas)}")
print(f"  Binaries: {len(binaries)}")

# Analysis configuration
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
    optimize=2,
)

# Remove duplicate entries
pyz = PYZ(a.pure)

# Platform-specific executable configuration
if sys.platform == 'win32':
    exe_name = 'AndroidTVTimeFixer.exe'
    console = True
    icon = None  # Add icon path if available
elif sys.platform == 'darwin':
    exe_name = 'AndroidTVTimeFixer'
    console = False
    icon = None  # Add icon path if available
else:  # Linux
    exe_name = 'AndroidTVTimeFixer'
    console = True
    icon = None

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name=exe_name,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=console,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=icon,
)

# Collect into directory
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='AndroidTVTimeFixer',
)

# macOS specific: Create application bundle
if sys.platform == 'darwin':
    app = BUNDLE(
        coll,
        name='AndroidTVTimeFixer.app',
        icon=icon,
        bundle_identifier='com.orientalium.androidtvtimefixer',
        info_plist={
            'CFBundleShortVersionString': '1.1.0',
            'CFBundleVersion': '1.1.0',
            'NSHighResolutionCapable': 'True',
            'LSMinimumSystemVersion': '10.13.0',
        },
    )

print("\n✓ PyInstaller spec file loaded successfully")
