"""Базовый класс для runtime hooks PyInstaller"""
import os
import sys
import logging
import stat
from pathlib import Path
from typing import Optional, List


class BaseRuntimeHook:
    """Базовый класс для настройки окружения при запуске"""
    
    def __init__(self, platform_name: str):
        self.platform = platform_name
        self.logger = self._setup_logger()
        self.base_path = self._get_base_path()
        self.resources_path = os.path.join(self.base_path, 'resources')
        
    def _setup_logger(self) -> logging.Logger:
        """Настройка логгера"""
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
        )
        return logging.getLogger(f'{self.platform}Hook')
    
    def _get_base_path(self) -> str:
        """Получение базового пути приложения"""
        return getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
    
    def _verify_file_exists(self, filepath: str, file_type: str = "file") -> None:
        """Проверка существования файла"""
        if not os.path.exists(filepath):
            raise FileNotFoundError(f"{file_type} not found at {filepath}")
    
    def _make_executable(self, filepath: str) -> None:
        """Установка прав на выполнение"""
        try:
            current_mode = os.stat(filepath).st_mode
            os.chmod(filepath, current_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            self.logger.debug(f"Made {filepath} executable")
        except Exception as e:
            self.logger.error(f"Failed to make {filepath} executable: {e}")
            raise
    
    def _configure_path(self, additional_paths: List[str]) -> None:
        """Настройка переменной PATH"""
        current_path = os.environ.get('PATH', '')
        all_paths = additional_paths + ([current_path] if current_path else [])
        os.environ['PATH'] = os.pathsep.join(all_paths)
        self.logger.info("PATH configured")
    
    def setup(self) -> None:
        """Основной метод настройки - переопределяется в подклассах"""
        raise NotImplementedError("Must be implemented in subclass")
