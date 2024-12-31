import os
import sys
import logging
import ctypes
from pathlib import Path
from typing import Set

def setup_windows_environment() -> None:
    logger = _setup_logger()
    try:
        base_path = _get_base_path()
        resources_path = os.path.join(base_path, 'resources')
        
        # Проверяем наличие ресурсов
        required_files = {'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'}
        _verify_resources(resources_path, required_files, logger)
        
        # Настраиваем окружение
        paths = [base_path, resources_path]
        _update_path(paths, logger)
        
        # Проверяем и загружаем DLL
        _load_windows_dlls(resources_path, logger)
        
        # Настраиваем переменные окружения для ADB
        os.environ['ANDROID_HOME'] = resources_path
        
        logger.info("Windows environment setup completed")
        
    except Exception as e:
        logger.error(f"Failed to setup Windows environment: {e}")
        raise SystemExit(1)

def _setup_logger() -> logging.Logger:
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger('WindowsHook')

def _get_base_path() -> str:
    return getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))

def _verify_resources(resources_path: str, required_files: Set[str], logger: logging.Logger) -> None:
    for file in required_files:
        file_path = os.path.join(resources_path, file)
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Required file not found: {file}")
        if file.endswith('.exe'):
            if not os.access(file_path, os.X_OK):
                logger.warning(f"Setting executable permissions for {file}")
                os.chmod(file_path, 0o755)

def _update_path(paths: list, logger: logging.Logger) -> None:
    current_path = os.environ.get('PATH', '')
    new_paths = os.pathsep.join(paths)
    
    if current_path:
        os.environ['PATH'] = new_paths + os.pathsep + current_path
    else:
        os.environ['PATH'] = new_paths
    
    logger.info("PATH environment updated")

def load_windows_dlls(resources_path: str, logger: logging.Logger) -> None:
    # Get full paths for DLLs
    adb_dll = os.path.join(resources_path, 'AdbWinApi.dll')
    usb_dll = os.path.join(resources_path, 'AdbWinUsbApi.dll')
    
    # Ensure files exist
    if not os.path.exists(adb_dll):
        raise FileNotFoundError(f"Required DLL not found: {adb_dll}")
    if not os.path.exists(usb_dll):
        raise FileNotFoundError(f"Required DLL not found: {usb_dll}")
    
    # Copy DLLs to a stable location
    stable_dir = os.path.join(os.path.expanduser('~'), '.android')
    os.makedirs(stable_dir, exist_ok=True)
    
    stable_adb_dll = os.path.join(stable_dir, 'AdbWinApi.dll')
    stable_usb_dll = os.path.join(stable_dir, 'AdbWinUsbApi.dll')
    
    # Copy files if they don't exist or are different
    import shutil
    shutil.copy2(adb_dll, stable_adb_dll)
    shutil.copy2(usb_dll, stable_usb_dll)
    
    # Load DLLs from stable location
    if not ctypes.windll.kernel32.LoadLibraryW(stable_adb_dll):
        raise OSError(f"Failed to load {stable_adb_dll}")
    if not ctypes.windll.kernel32.LoadLibraryW(stable_usb_dll):
        raise OSError(f"Failed to load {stable_usb_dll}")
    
    logger.info("Windows DLLs loaded successfully")