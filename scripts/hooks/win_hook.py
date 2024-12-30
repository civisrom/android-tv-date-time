import os
import sys

if sys.platform == 'win32':
    try:
        if hasattr(sys, '_MEIPASS'):
            # Проверяем наличие переменной PATH и корректируем её
            if 'PATH' in os.environ:
                os.environ['PATH'] = os.path.join(sys._MEIPASS) + os.pathsep + os.environ['PATH']
            else:
                os.environ['PATH'] = os.path.join(sys._MEIPASS)
    except Exception as e:
        print(f"Error configuring PATH for Windows: {e}")
