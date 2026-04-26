import os
import sys
import logging
from typing import Set

# Global ADB path for import by android_time_fixer.py
ADB_PATH = os.path.join(
    getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__))),
    'resources', 'adb.exe'
)
_DLL_DIRECTORY_HANDLES = []

def setup_windows_environment() -> None:
    logger = setup_logger()
    try:
        base_path = get_base_path()
        resources_path = os.path.join(base_path, 'resources')
        
        required_files = {'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'}
        verify_resources(resources_path, required_files, logger)
        
        paths = [base_path, resources_path]
        update_path(paths, logger)
        
        configure_windows_dll_search(resources_path, logger)
        
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

def configure_windows_dll_search(resources_path: str, logger: logging.Logger) -> None:
    for dll_name in ['AdbWinApi.dll', 'AdbWinUsbApi.dll']:
        dll_path = os.path.join(resources_path, dll_name)
        if not os.path.exists(dll_path):
            raise FileNotFoundError(f"Required DLL not found: {dll_path}")

    try:
        _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(resources_path))
        logger.info("Windows DLL search path updated")
    except (AttributeError, OSError) as e:
        # PATH already contains resources_path; that is enough for adb.exe and
        # keeps startup from failing on systems that reject add_dll_directory.
        logger.warning(f"Could not update DLL search path: {e}")

if getattr(sys, 'frozen', False) or __name__ == '__main__':
    setup_windows_environment()
