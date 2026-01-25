import os
import sys
import logging
import ctypes
from pathlib import Path
from typing import Set

# Global ADB path for import by android_time_fixer.py
ADB_PATH = os.path.join(
    getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__))),
    'resources', 'adb.exe'
)

def setup_windows_environment() -> None:
    logger = setup_logger()
    try:
        base_path = get_base_path()
        resources_path = os.path.join(base_path, 'resources')
        
        required_files = {'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'}
        verify_resources(resources_path, required_files, logger)
        
        paths = [base_path, resources_path]
        update_path(paths, logger)
        
        load_windows_dlls(resources_path, logger)
        
        os.environ['ANDROID_HOME'] = resources_path
        
        logger.info("Windows environment setup completed")
        
    except Exception as e:
        logger.error(f"Failed to setup Windows environment: {e}")
        raise SystemExit(1)

def setup_logger() -> logging.Logger:
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger('WindowsHook')

def get_base_path() -> str:
    return getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))

def verify_resources(resources_path: str, required_files: Set[str], logger: logging.Logger) -> None:
    for file in required_files:
        file_path = os.path.join(resources_path, file)
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Required file not found: {file}")
        if file.endswith('.exe'):
            if not os.access(file_path, os.X_OK):
                logger.warning(f"Setting executable permissions for {file}")
                os.chmod(file_path, 0o755)

def update_path(paths: list, logger: logging.Logger) -> None:
    current_path = os.environ.get('PATH', '')
    new_paths = os.pathsep.join(paths)
    
    if current_path:
        os.environ['PATH'] = new_paths + os.pathsep + current_path
    else:
        os.environ['PATH'] = new_paths
    
    logger.info("PATH environment updated")

def load_windows_dlls(resources_path: str, logger: logging.Logger) -> None:
    stable_dir = os.path.join(os.path.expanduser('~'), '.android')
    os.makedirs(stable_dir, exist_ok=True)
    
    for dll_name in ['AdbWinApi.dll', 'AdbWinUsbApi.dll']:
        source_dll = os.path.join(resources_path, dll_name)
        target_dll = os.path.join(stable_dir, dll_name)
        
        if not os.path.exists(source_dll):
            raise FileNotFoundError(f"Required DLL not found: {source_dll}")
            
        import shutil
        shutil.copy2(source_dll, target_dll)
        
        if not ctypes.windll.kernel32.LoadLibraryW(target_dll):
            raise OSError(f"Failed to load {target_dll}")
    
    logger.info("Windows DLLs loaded successfully")