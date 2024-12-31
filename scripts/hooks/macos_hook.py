import os
import sys
import logging
import stat
import subprocess
from pathlib import Path
from typing import Optional

def setup_macos_environment() -> None:
    logger = _setup_logger()
    try:
        base_path = _get_base_path()
        resources_path = os.path.join(base_path, 'resources')
        
        # Проверяем ADB
        adb_path = os.path.join(resources_path, 'adb')
        _verify_adb(adb_path, logger)
        
        # Настраиваем окружение
        _configure_environment(base_path, resources_path, logger)
        
        # Проверяем и настраиваем безопасность
        _configure_security(adb_path, logger)
        
        logger.info("MacOS environment setup completed")
        
    except Exception as e:
        logger.error(f"Failed to setup MacOS environment: {e}")
        raise SystemExit(1)

def _setup_logger() -> logging.Logger:
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger('MacOSHook')

def _get_base_path() -> str:
    return getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))

def _verify_adb(adb_path: str, logger: logging.Logger) -> None:
    if not os.path.exists(adb_path):
        raise FileNotFoundError(f"ADB not found at {adb_path}")
    
    # Устанавливаем права на выполнение
    current_mode = os.stat(adb_path).st_mode
    os.chmod(adb_path, current_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    logger.info("ADB permissions set")

def _configure_environment(base_path: str, resources_path: str, logger: logging.Logger) -> None:
    # Настраиваем PATH
    paths = [base_path, resources_path]
    current_path = os.environ.get('PATH', '')
    new_path = os.pathsep.join(paths + [current_path] if current_path else paths)
    os.environ['PATH'] = new_path
    
    # Настраиваем DYLD_LIBRARY_PATH
    lib_path = os.path.join(base_path, 'lib')
    if os.path.exists(lib_path):
        current_dyld_path = os.environ.get('DYLD_LIBRARY_PATH', '')
        new_dyld_path = os.pathsep.join([lib_path, current_dyld_path]) if current_dyld_path else lib_path
        os.environ['DYLD_LIBRARY_PATH'] = new_dyld_path
    
    os.environ['ANDROID_HOME'] = resources_path
    logger.info("Environment variables configured")

def _configure_security(adb_path: str, logger: logging.Logger) -> None:
    try:
        # Проверяем карантин
        result = subprocess.run(['xattr', adb_path], capture_output=True, text=True)
        if 'com.apple.quarantine' in result.stdout:
            subprocess.run(['xattr', '-d', 'com.apple.quarantine', adb_path])
            logger.info("Quarantine attribute removed from ADB")
            
        # Проверяем подпись
        result = subprocess.run(['codesign', '-v', adb_path], capture_output=True)
        if result.returncode != 0:
            subprocess.run(['codesign', '-s', '-', '--force', adb_path])
            logger.info("ADB binary signed")
            
    except Exception as e:
        logger.warning(f"Could not configure security settings: {e}")

if __name__ == '__main__':
    setup_macos_environment()