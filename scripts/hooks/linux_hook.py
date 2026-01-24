import os
import sys
import logging
import stat
from pathlib import Path
from typing import List

def setup_linux_environment() -> None:
    logger = _setup_logger()
    try:
        base_path = _get_base_path()
        resources_path = os.path.join(base_path, 'resources')
        
        # Проверяем ADB
        adb_path = os.path.join(resources_path, 'adb')
        _verify_adb(adb_path, logger)
        
        # Настраиваем окружение
        _configure_environment(base_path, resources_path, logger)
        
        # Проверяем USB правила
        _setup_udev_rules(logger)
        
        logger.info("Linux environment setup completed")
        
    except Exception as e:
        logger.error(f"Failed to setup Linux environment: {e}")
        raise SystemExit(1)

def _setup_logger() -> logging.Logger:
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger('LinuxHook')

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
    
    # Настраиваем LD_LIBRARY_PATH
    lib_path = os.path.join(base_path, 'lib')
    if os.path.exists(lib_path):
        current_ld_path = os.environ.get('LD_LIBRARY_PATH', '')
        new_ld_path = os.pathsep.join([lib_path, current_ld_path]) if current_ld_path else lib_path
        os.environ['LD_LIBRARY_PATH'] = new_ld_path
    
    os.environ['ANDROID_HOME'] = resources_path
    logger.info("Environment variables configured")

def _setup_udev_rules(logger: logging.Logger) -> None:
    try:
        if os.geteuid() == 0:  # Проверяем root права
            rules_content = 'SUBSYSTEM=="usb", ATTR{idVendor}=="0502", MODE="0666", GROUP="plugdev"\n'
            rules_path = '/etc/udev/rules.d/51-android.rules'
            
            if not os.path.exists(rules_path):
                with open(rules_path, 'w') as f:
                    f.write(rules_content)
                os.system('udevadm control --reload-rules')
                logger.info("USB rules installed")
    except Exception as e:
        logger.warning(f"Could not setup udev rules: {e}")

if __name__ == '__main__':
    setup_linux_environment()