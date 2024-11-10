import os
import shutil
from pathlib import Path
import subprocess
import sys

def check_files():
    """Check if all required files exist"""
    required_files = ['android_time_fixer.py']
    missing_files = [f for f in required_files if not os.path.exists(f)]
    if missing_files:
        print("Missing required files:", missing_files)
        return False
    return True

def install_requirements():
    """Install required packages using pip"""
    requirements = ['pyinstaller', 'adb-shell']
    try:
        for package in requirements:
            subprocess.check_call([sys.executable, '-m', 'pip', 'install', package])
        return True
    except subprocess.CalledProcessError as e:
        print(f"Failed to install requirements: {e}")
        return False

def build_executable():
    """Build the executable using PyInstaller"""
    try:
        # Clean up previous builds
        for path in ['build', 'dist']:
            if os.path.exists(path):
                shutil.rmtree(path)
        if os.path.exists('AndroidTVTimeFixer.spec'):
            os.remove('AndroidTVTimeFixer.spec')

        # Build command
        cmd = [
            sys.executable,
            '-m',
            'PyInstaller',
            '--onefile',
            '--console',
            '--name=AndroidTVTimeFixer',
            'android_time_fixer.py'
        ]

        # Execute build
        subprocess.check_call(cmd)
        
        # Make executable
        executable_path = os.path.join('dist', 'AndroidTVTimeFixer')
        if os.path.exists(executable_path):
            os.chmod(executable_path, 0o755)
            return True
        return False
    except subprocess.CalledProcessError as e:
        print(f"Build failed: {e}")
        return False

def main():
    # Change to script directory
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    print("Starting build process...")
    
    # Check required files
    if not check_files():
        print("Required files missing. Aborting build.")
        return
    
    # Install requirements
    print("Installing requirements...")
    if not install_requirements():
        print("Failed to install requirements. Aborting build.")
        return
    
    # Build executable
    print("Building executable...")
    if build_executable():
        print("\nBuild successful!")
        print(f"Executable created at: {os.path.abspath(os.path.join('dist', 'AndroidTVTimeFixer'))}")
    else:
        print("\nBuild failed!")

if __name__ == '__main__':
    main()
