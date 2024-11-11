import os
import shutil
from pathlib import Path
import subprocess
import sys

def check_files():
    """Проверяет, что все необходимые файлы существуют"""
    required_files = ['android_tv_time_fixer.py']
    missing_files = [f for f in required_files if not os.path.exists(f)]
    if missing_files:
        print("Отсутствуют необходимые файлы:", missing_files)
        return False
    return True

def install_requirements():
    """Устанавливает необходимые пакеты с помощью pip"""
    requirements = ['pyinstaller', 'adb-shell']
    try:
        for package in requirements:
            subprocess.check_call([sys.executable, '-m', 'pip', 'install', package])
        return True
    except subprocess.CalledProcessError as e:
        print(f"Не удалось установить необходимые пакеты: {e}")
        return False

def build_executable():
    """Создает исполняемый файл с помощью PyInstaller"""
    try:
        # Очищаем предыдущие сборки
        for path in ['build', 'dist']:
            if os.path.exists(path):
                shutil.rmtree(path)
        if os.path.exists('AndroidTVTimeFixer.spec'):
            os.remove('AndroidTVTimeFixer.spec')

        # Команда сборки
        cmd = [
            sys.executable,
            '-m',
            'PyInstaller',
            '--onefile',
            '--console',
            '--name=AndroidTVTimeFixer',
            'android_tv_time_fixer.py'
        ]

        # Выполняем сборку
        subprocess.check_call(cmd)
        
        # Делаем файл исполняемым
        executable_path = os.path.join('dist', 'AndroidTVTimeFixer')
        if os.path.exists(executable_path):
            os.chmod(executable_path, 0o755)
            return True
        return False
    except subprocess.CalledProcessError as e:
        print(f"Сборка не удалась: {e}")
        return False

def main():
    # Переходим в директорию скрипта
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    print("Запускаем процесс сборки...")
    
    # Проверяем необходимые файлы
    if not check_files():
        print("Отсутствуют необходимые файлы. Прерываем сборку.")
        return
    
    # Устанавливаем необходимые пакеты
    print("Устанавливаем необходимые пакеты...")
    if not install_requirements():
        print("Не удалось установить необходимые пакеты. Прерываем сборку.")
        return
    
    # Собираем исполняемый файл
    print("Собираем исполняемый файл...")
    if build_executable():
        print("\nСборка прошла успешно!")
        print(f"Исполняемый файл создан по пути: {os.path.abspath(os.path.join('dist', 'AndroidTVTimeFixer'))}")
    else:
        print("\nСборка не удалась!")

if __name__ == '__main__':
    main()
