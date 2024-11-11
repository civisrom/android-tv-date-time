import pytest
from pathlib import Path
import sys

# Добавляем корневую директорию проекта в PYTHONPATH
root_dir = Path(__file__).parent.parent
sys.path.append(str(root_dir))

# Импортируем основной модуль
from android_time_fixer import AndroidTVTimeFixer

def test_android_tv_time_fixer_initialization():
    """Тест инициализации основного класса"""
    fixer = AndroidTVTimeFixer()
    assert isinstance(fixer, AndroidTVTimeFixer)

def test_android_tv_time_fixer_exists():
    """Тест наличия основного файла проекта"""
    main_file = root_dir / "android_time_fixer.py"
    assert main_file.exists(), "Main application file not found"

def test_required_attributes():
    """Тест наличия необходимых атрибутов в классе"""
    fixer = AndroidTVTimeFixer()
    required_attributes = [
        'connect_to_device',
        'update_time',
        'get_device_time'
    ]
    for attr in required_attributes:
        assert hasattr(fixer, attr), f"Missing required attribute: {attr}"
