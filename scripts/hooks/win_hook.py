"""Runtime hook для Windows"""
import os
import sys
import ctypes
from pathlib import Path

# Добавляем путь к базовому модулю
from .base_hook import BaseRuntimeHook


class WindowsRuntimeHook(BaseRuntimeHook):
    """Настройка окружения для Windows"""
    
    REQUIRED_FILES = {'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'}
    
    def __init__(self):
        super().__init__('Windows')
        
    def setup(self) -> None:
        """Настройка Windows окружения"""
        try:
            self._verify_resources()
            self._configure_environment()
            self._load_adb_dlls()
            self.logger.info("Windows environment setup completed")
        except Exception as e:
            self.logger.error(f"Failed to setup Windows environment: {e}")
            raise SystemExit(1)
    
    def _verify_resources(self) -> None:
        """Проверка наличия необходимых файлов"""
        for filename in self.REQUIRED_FILES:
            filepath = os.path.join(self.resources_path, filename)
            self._verify_file_exists(filepath, filename)
            
            # Проверяем exe файлы на возможность выполнения
            if filename.endswith('.exe'):
                if not os.access(filepath, os.X_OK):
                    self.logger.warning(f"Setting executable permissions for {filename}")
                    try:
                        os.chmod(filepath, 0o755)
                    except Exception as e:
                        self.logger.error(f"Failed to set permissions: {e}")
        
        self.logger.info("All required files verified")
    
    def _configure_environment(self) -> None:
        """Настройка переменных окружения"""
        # Настраиваем PATH
        self._configure_path([self.base_path, self.resources_path])
        
        # Устанавливаем ANDROID_HOME
        os.environ['ANDROID_HOME'] = self.resources_path
        self.logger.info("Environment variables configured")
    
    def _load_adb_dlls(self) -> None:
        """Загрузка ADB DLL библиотек"""
        # Создаем стабильную директорию для DLL
        stable_dir = Path.home() / '.android'
        stable_dir.mkdir(parents=True, exist_ok=True)
        
        import shutil
        
        for dll_name in ['AdbWinApi.dll', 'AdbWinUsbApi.dll']:
            source_path = os.path.join(self.resources_path, dll_name)
            target_path = stable_dir / dll_name
            
            # Копируем DLL в стабильную директорию
            if not target_path.exists() or self._should_update_dll(source_path, target_path):
                try:
                    shutil.copy2(source_path, target_path)
                    self.logger.info(f"Copied {dll_name} to {stable_dir}")
                except Exception as e:
                    self.logger.warning(f"Failed to copy {dll_name}: {e}")
            
            # Загружаем DLL
            try:
                if not ctypes.windll.kernel32.LoadLibraryW(str(target_path)):
                    raise OSError(f"LoadLibraryW failed for {target_path}")
                self.logger.debug(f"Loaded {dll_name}")
            except Exception as e:
                self.logger.error(f"Failed to load {dll_name}: {e}")
                raise
        
        self.logger.info("Windows DLLs loaded successfully")
    
    def _should_update_dll(self, source: str, target: Path) -> bool:
        """Проверка, нужно ли обновить DLL"""
        try:
            source_mtime = os.path.getmtime(source)
            target_mtime = target.stat().st_mtime
            return source_mtime > target_mtime
        except Exception:
            return True


def setup_windows_environment() -> None:
    """Точка входа для runtime hook"""
    hook = WindowsRuntimeHook()
    hook.setup()


if __name__ == '__main__':
    setup_windows_environment()
