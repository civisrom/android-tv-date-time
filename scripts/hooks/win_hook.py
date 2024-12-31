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

def _load_windows_dlls(resources_path: str, logger: logging.Logger) -> None:
    dll_path = os.path.join(resources_path, 'AdbWinApi.dll')
    if not ctypes.windll.kernel32.LoadLibraryW(dll_path):
        raise OSError(f"Failed to load {dll_path}")
    
    dll_path = os.path.join(resources_path, 'AdbWinUsbApi.dll')
    if not ctypes.windll.kernel32.LoadLibraryW(dll_path):
        raise OSError(f"Failed to load {dll_path}")
    
    logger.info("Windows DLLs loaded successfully")

if __name__ == '__main__':
    setup_windows_environment()