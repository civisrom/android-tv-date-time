import os
import sys
import shutil
import tempfile
import atexit

def extract_adb():
    """Извлекает ADB во временную директорию"""
    # Получаем путь к временной директории
    temp_dir = tempfile.mkdtemp()
    
    # Получаем путь к ресурсам в собранном приложении
    if getattr(sys, 'frozen', False):
        # Если приложение собрано
        base_path = sys._MEIPASS
    else:
        # Если запущено как скрипт
        base_path = os.path.abspath(os.path.dirname(__file__))

    # Путь к ADB в ресурсах
    resource_path = os.path.join(base_path, 'resources')
    adb_path = os.path.join(resource_path, 'adb')

    # Копируем ADB во временную директорию
    if os.path.exists(adb_path):
        temp_adb = os.path.join(temp_dir, 'adb')
        shutil.copy2(adb_path, temp_adb)
        os.chmod(temp_adb, 0o755)  # Устанавливаем права на выполнение

    # Регистрируем функцию очистки
    def cleanup():
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
        except:
            pass

    atexit.register(cleanup)

    # Добавляем временную директорию в PATH
    os.environ['PATH'] = temp_dir + os.pathsep + os.environ.get('PATH', '')
    
    return os.path.join(temp_dir, 'adb')

# Извлекаем ADB при запуске
ADB_PATH = extract_adb()
