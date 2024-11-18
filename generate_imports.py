import pkg_resources
import importlib
import sys
import os
import json
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def safe_import_module(module_name):
    """Безопасный импорт модуля с обработкой ошибок."""
    try:
        return importlib.import_module(module_name)
    except ImportError:
        logger.warning(f"Could not import module: {module_name}")
        return None

def get_package_hidden_imports(package_name, depth=2):
    """
    Рекурсивное извлечение скрытых импортов с ограничением глубины.
    
    Args:
        package_name (str): Имя пакета
        depth (int): Максимальная глубина рекурсии
    
    Returns:
        list: Список hidden-import директив
    """
    hidden_imports = []
    tried_modules = set()

    def recursive_import_search(current_module, current_depth=0):
        if current_depth > depth or current_module in tried_modules:
            return []

        tried_modules.add(current_module)
        hidden_imports = [f"--hidden-import={current_module}"]

        try:
            module = safe_import_module(current_module)
            if module:
                # Поиск подмодулей
                for attr_name in dir(module):
                    try:
                        attr = getattr(module, attr_name)
                        if hasattr(attr, '__module__'):
                            sub_module = attr.__module__
                            if sub_module.startswith(package_name) and sub_module not in tried_modules:
                                if current_depth < depth:
                                    hidden_imports.extend(
                                        recursive_import_search(sub_module, current_depth + 1)
                                    )
                    except Exception as e:
                        logger.debug(f"Error processing {attr_name}: {e}")
        except Exception as e:
            logger.warning(f"Error in recursive import for {current_module}: {e}")

        return list(set(hidden_imports))

    try:
        package = safe_import_module(package_name)
        if package:
            hidden_imports = recursive_import_search(package_name)
    except Exception as e:
        logger.error(f"Critical error processing {package_name}: {e}")

    return list(set(hidden_imports))

def generate_pyinstaller_config():
    """Генерация конфигурации для PyInstaller."""
    CRITICAL_PACKAGES = [
        'adb_shell', 'pyperclip', 'colorama', 'platformdirs', 
        'cryptography', 'usb', 'rsa', 'aiofiles', 
        'async_timeout', 'asyncio', 'packaging'
    ]

    all_hidden_imports = []
    for package in CRITICAL_PACKAGES:
        package_imports = get_package_hidden_imports(package)
        all_hidden_imports.extend(package_imports)

    # Дополнительные специфические hidden-import
    additional_imports = [
        '--hidden-import=usb.core',
        '--hidden-import=usb.util',
        '--hidden-import=socket',
        '--hidden-import=subprocess',
        '--hidden-import=threading'
    ]
    
    all_hidden_imports.extend(additional_imports)

    # Уникализация и сортировка
    unique_imports = sorted(set(all_hidden_imports))

    # Сохранение результатов
    with open('pyinstaller_imports.txt', 'w') as f:
        f.write('\n'.join(unique_imports))

    logger.info(f"Сгенерировано {len(unique_imports)} hidden-import директив")
    return unique_imports

if __name__ == "__main__":
    generate_pyinstaller_config()
