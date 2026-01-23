"""Runtime hook для Linux"""
import os
import sys
import subprocess

# Добавляем путь к базовому модулю
sys.path.insert(0, os.path.dirname(__file__))
from base_hook import BaseRuntimeHook


class LinuxRuntimeHook(BaseRuntimeHook):
    """Настройка окружения для Linux"""
    
    def __init__(self):
        super().__init__('Linux')
        
    def setup(self) -> None:
        """Настройка Linux окружения"""
        try:
            self._verify_adb()
            self._configure_environment()
            self._setup_udev_rules()
            self.logger.info("Linux environment setup completed")
        except Exception as e:
            self.logger.error(f"Failed to setup Linux environment: {e}")
            raise SystemExit(1)
    
    def _verify_adb(self) -> None:
        """Проверка и настройка ADB"""
        adb_path = os.path.join(self.resources_path, 'adb')
        self._verify_file_exists(adb_path, "ADB")
        self._make_executable(adb_path)
        self.logger.info("ADB verified and configured")
    
    def _configure_environment(self) -> None:
        """Настройка переменных окружения"""
        # Настраиваем PATH
        self._configure_path([self.base_path, self.resources_path])
        
        # Настраиваем LD_LIBRARY_PATH
        lib_path = os.path.join(self.base_path, 'lib')
        if os.path.exists(lib_path):
            current_ld = os.environ.get('LD_LIBRARY_PATH', '')
            new_ld = f"{lib_path}{os.pathsep}{current_ld}" if current_ld else lib_path
            os.environ['LD_LIBRARY_PATH'] = new_ld
            self.logger.debug("LD_LIBRARY_PATH configured")
        
        # Устанавливаем ANDROID_HOME
        os.environ['ANDROID_HOME'] = self.resources_path
        self.logger.info("Environment variables configured")
    
    def _setup_udev_rules(self) -> None:
        """Настройка udev правил для USB устройств"""
        # Проверяем, запущены ли мы с правами root
        if os.geteuid() != 0:
            self.logger.debug("Not running as root, skipping udev rules setup")
            return
        
        try:
            rules_content = (
                '# Android TV Time Fixer rules\n'
                'SUBSYSTEM=="usb", ATTR{idVendor}=="0502", MODE="0666", GROUP="plugdev"\n'
                'SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", MODE="0666", GROUP="plugdev"\n'
            )
            rules_path = '/etc/udev/rules.d/51-android-timefixer.rules'
            
            # Проверяем, существуют ли уже правила
            if os.path.exists(rules_path):
                with open(rules_path, 'r') as f:
                    if f.read() == rules_content:
                        self.logger.debug("Udev rules already up to date")
                        return
            
            # Создаем/обновляем правила
            with open(rules_path, 'w') as f:
                f.write(rules_content)
            
            # Перезагружаем правила
            subprocess.run(['udevadm', 'control', '--reload-rules'], 
                          check=False, capture_output=True)
            subprocess.run(['udevadm', 'trigger'], 
                          check=False, capture_output=True)
            
            self.logger.info("USB udev rules installed and reloaded")
            
        except PermissionError:
            self.logger.warning("Insufficient permissions to setup udev rules")
        except Exception as e:
            self.logger.warning(f"Could not setup udev rules: {e}")


def setup_linux_environment() -> None:
    """Точка входа для runtime hook"""
    hook = LinuxRuntimeHook()
    hook.setup()


if __name__ == '__main__':
    setup_linux_environment()
