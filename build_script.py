import os
import shutil
import subprocess
import sys
import logging
from pathlib import Path
from typing import List, Optional
from contextlib import contextmanager

# Настройка логирования для CI/CD
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    force=True
)
logger = logging.getLogger(__name__)

class CIBuildError(Exception):
    """Пользовательское исключение для ошибок сборки в CI"""
    pass

class GithubBuilder:
    def __init__(self):
        self.script_path = Path('android_time_fixer.py')
        self.build_dir = Path('build')
        self.dist_dir = Path('dist')
        self.artifacts_dir = Path('artifacts')
        self.executable_name = 'AndroidTVTimeFixer'
        self.requirements = ['pyinstaller', 'adb-shell']
        
        # Определяем расширение файла в зависимости от ОС
        self.executable_extension = '.exe' if sys.platform == 'win32' else ''
        
        # Настройки для разных платформ
        self.platform = sys.platform
        self.is_windows = self.platform == 'win32'
        self.is_linux = self.platform.startswith('linux')
        self.is_macos = self.platform == 'darwin'

    def setup_ci_environment(self) -> None:
        """Настраивает окружение для CI"""
        try:
            # Создаем директорию для артефактов
            self.artifacts_dir.mkdir(exist_ok=True)
            
            # Устанавливаем переменные окружения для PyInstaller
            os.environ['PYTHONPATH'] = str(Path.cwd())
            
            # Проверяем наличие Python
            python_version = sys.version.split()[0]
            logger.info(f"Используется Python {python_version}")
            
            # Проверяем окружение CI
            if os.getenv('GITHUB_ACTIONS'):
                logger.info("Запуск в GitHub Actions")
            
        except Exception as e:
            raise CIBuildError(f"Ошибка настройки CI окружения: {e}")

    def install_requirements(self) -> bool:
        """Устанавливает зависимости в CI окружении"""
        try:
            # Обновляем pip
            subprocess.run([sys.executable, '-m', 'pip', 'install', '--upgrade', 'pip'],
                         check=True, capture_output=True, text=True)
            
            # Устанавливаем зависимости с кэшированием
            requirements_txt = Path('requirements.txt')
            if not requirements_txt.exists():
                with open(requirements_txt, 'w') as f:
                    f.write('\n'.join(self.requirements))
            
            subprocess.run([sys.executable, '-m', 'pip', 'install', '-r', 'requirements.txt'],
                         check=True, capture_output=True, text=True)
            
            return True
        except subprocess.CalledProcessError as e:
            logger.error(f"Ошибка установки зависимостей: {e.stderr}")
            return False

    def build_executable(self) -> bool:
        """Создает исполняемый файл для текущей платформы"""
        try:
            # Базовые параметры PyInstaller
            cmd = [
                sys.executable,
                '-m',
                'PyInstaller',
                '--onefile',
                '--clean',
                '--log-level=WARN',
                f'--name={self.executable_name}',
                str(self.script_path)
            ]
            
            # Добавляем специфичные для платформы параметры
            if self.is_windows:
                cmd.extend(['--noconsole'])
            
            result = subprocess.run(cmd, check=True, capture_output=True, text=True)
            
            # Формируем имя исполняемого файла с учетом платформы
            executable_name = f"{self.executable_name}{self.executable_extension}"
            executable_path = self.dist_dir / executable_name
            
            if not executable_path.exists():
                raise CIBuildError(f"Исполняемый файл не создан: {executable_path}")
            
            # Копируем артефакт в специальную директорию
            artifact_path = self.artifacts_dir / f"{self.executable_name}_{self.platform}{self.executable_extension}"
            shutil.copy2(executable_path, artifact_path)
            
            logger.info(f"Артефакт создан: {artifact_path}")
            return True
            
        except Exception as e:
            logger.error(f"Ошибка сборки: {e}")
            return False

    def run_ci_build(self) -> bool:
        """Запускает процесс сборки в CI"""
        try:
            logger.info(f"Начало сборки для платформы: {self.platform}")
            
            self.setup_ci_environment()
            
            if not self.install_requirements():
                raise CIBuildError("Ошибка установки зависимостей")
            
            if not self.build_executable():
                raise CIBuildError("Ошибка сборки исполняемого файла")
            
            logger.info("Сборка успешно завершена")
            return True
            
        except Exception as e:
            logger.error(f"Ошибка CI сборки: {e}")
            return False

def main():
    try:
        builder = GithubBuilder()
        success = builder.run_ci_build()
        sys.exit(0 if success else 1)
    except Exception as e:
        logger.critical(f"Критическая ошибка: {e}")
        sys.exit(1)

if __name__ == '__main__':
    main()
