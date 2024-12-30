# scripts/hooks/win_hook.py
import os
import sys

if sys.platform == 'win32':
    if hasattr(sys, '_MEIPASS'):
        os.environ['PATH'] = os.path.dirname(sys._MEIPASS) + os.pathsep + os.environ['PATH']