"""Runtime hook для macOS"""
import os
import sys
import subprocess

# Добавляем путь к базовому модулю
sys.path.insert(0, os.path.dirname(__file__))
from base_hook import BaseRuntimeHook


class MacOSRuntimeHook(BaseRuntimeHook):
    """Настройка окружения для macOS"""
    
    def __init__(self):
        super().__init__('MacOS')
        
    def setup(self) -> None:
        """Настройка macOS окружения"""
        try:
            self._verify_adb()
            self._configure_environment()
            self._configure_security()
            self.logger.info("MacOS environment setup completed")
        except Exception as e:
            self.logger.error(f"Failed to setup MacOS environment: {e}")
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
        
        # Настраиваем DYLD_LIBRARY_PATH для динамических библиотек
        lib_path = os.path.join(self.base_path, 'lib')
        if os.path.exists(lib_path):
            current_dyld = os.environ.get('DYLD_LIBRARY_PATH', '')
            new_dyld = f"{lib_path}{os.pathsep}{current_dyld}" if current_dyld else lib_path
            os.environ['DYLD_LIBRARY_PATH'] = new_dyld
            self.logger.debug("DYLD_LIBRARY_PATH configured")
        
        # Устанавливаем ANDROID_HOME
        os.environ['ANDROID_HOME'] = self.resources_path
        self.logger.info("Environment variables configured")
    
    def _configure_security(self) -> None:
        """Настройка безопасности macOS (карантин, подпись)"""
        adb_path = os.path.join(self.resources_path, 'adb')
        
        try:
            # Удаляем атрибут карантина, если он есть
            result = subprocess.run(
                ['xattr', '-l', adb_path],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode == 0 and 'com.apple.quarantine' in result.stdout:
                subprocess.run(
                    ['xattr', '-d', 'com.apple.quarantine', adb_path],
                    check=True,
                    capture_output=True,
                    timeout=5
                )
                self.logger.info("Quarantine attribute removed from ADB")
            
        except subprocess.TimeoutExpired:
            self.logger.warning("Quarantine check timed out")
        except subprocess.CalledProcessError as e:
            self.logger.debug(f"Could not remove quarantine attribute: {e}")
        except FileNotFoundError:
            self.logger.debug("xattr command not found")
        except Exception as e:
            self.logger.warning(f"Unexpected error removing quarantine: {e}")
        
        # Подписываем бинарник (ad-hoc подпись)
        try:
            result = subprocess.run(
                ['codesign', '-v', adb_path],
                capture_output=True,
                timeout=5
            )
            
            if result.returncode != 0:
                subprocess.run(
                    ['codesign', '-s', '-', '--force', '--deep', adb_path],
                    check=True,
                    capture_output=True,
                    timeout=10
                )
                self.logger.info("ADB binary signed (ad-hoc)")
                
        except subprocess.TimeoutExpired:
            self.logger.warning("Code signing timed out")
        except subprocess.CalledProcessError as e:
            self.logger.debug(f"Could not sign binary: {e}")
        except FileNotFoundError:
            self.logger.debug("codesign command not found")
        except Exception as e:
            self.logger.warning(f"Unexpected error during code signing: {e}")


def setup_macos_environment() -> None:
    """Точка входа для runtime hook"""
    hook = MacOSRuntimeHook()
    hook.setup()


if __name__ == '__main__':
    setup_macos_environment()
