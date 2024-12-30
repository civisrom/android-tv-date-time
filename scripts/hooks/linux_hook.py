import os
import sys

if sys.platform.startswith('linux'):
    try:
        if hasattr(sys, '_MEIPASS'):
            if 'PATH' in os.environ:
                os.environ['PATH'] = os.path.join(sys._MEIPASS) + os.pathsep + os.environ['PATH']
            else:
                os.environ['PATH'] = os.path.join(sys._MEIPASS)
    except Exception as e:
        print(f"Error configuring PATH for Linux: {e}")
