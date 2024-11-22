import os
import sys
import shutil
import tempfile
import atexit

def extract_adb():
    """Извлекает ADB и необходимые DLL во временную директорию"""
    # Получаем путь к временной директории
    temp_dir = tempfile.mkdtemp()
    
    # Получаем путь к ресурсам в собранном приложении
    if getattr(sys, 'frozen', False):
        # Если приложение собрано
        base_path = sys._MEIPASS
    else:
        # Если запущено как скрипт
        base_path = os.path.abspath(os.path.dirname(__file__))

    # Пути к файлам в ресурсах
    resource_path = os.path.join(base_path, 'resources')
    adb_path = os.path.join(resource_path, 'adb.exe')
    adbapi_path = os.path.join(resource_path, 'AdbWinApi.dll')
    adbusbapi_path = os.path.join(resource_path, 'AdbWinUsbApi.dll')

    # Копируем файлы во временную директорию
    if os.path.exists(adb_path):
        shutil.copy2(adb_path, temp_dir)
    if os.path.exists(adbapi_path):
        shutil.copy2(adbapi_path, temp_dir)
    if os.path.exists(adbusbapi_path):
        shutil.copy2(adbusbapi_path, temp_dir)

    # Регистрируем функцию очистки
    def cleanup():
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
        except:
            pass

    atexit.register(cleanup)

    # Добавляем временную директорию в PATH
    os.environ['PATH'] = temp_dir + os.pathsep + os.environ.get('PATH', '')
    
    return os.path.join(temp_dir, 'adb.exe')

# Извлекаем ADB при запуске
ADB_PATH = extract_adb()
