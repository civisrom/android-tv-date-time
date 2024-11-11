import pytest
import sys
from pathlib import Path

# Добавляем корневую директорию проекта в PYTHONPATH
root_dir = Path(__file__).parent.parent
sys.path.append(str(root_dir))

@pytest.fixture
def android_tv_time_fixer():
    """Фикстура для создания экземпляра AndroidTVTimeFixer"""
    from android_time_fixer import AndroidTVTimeFixer
    return AndroidTVTimeFixer()
