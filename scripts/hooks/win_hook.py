import os
import sys
import logging
import subprocess
from pathlib import Path
import winreg
import ctypes
from ctypes import windll


def get_bundle_dir():
    """Get the directory where the bundled application is located"""
    if getattr(sys, 'frozen', False):
        return Path(sys._MEIPASS)
    return Path(__file__).parent


def setup_logging():
    """Configure logging for the application"""
    logging.basicConfig(
        level=logging.DEBUG if os.environ.get('CI_DEBUG') else logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    return logging.getLogger('AndroidTVTimeFixer')


def is_admin():
    """Check if the current process has admin privileges"""
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False


def setup_adb_path():
    """Set up ADB path for Windows"""
    logger = setup_logging()
    bundle_dir = get_bundle_dir()
    resources_dir = bundle_dir / 'resources'
    
    # Ensure the resources directory exists
    if not resources_dir.exists():
        resources_dir.mkdir(parents=True, exist_ok=True)
    
    # Set ADB path in environment
    adb_path = resources_dir / 'adb.exe'
    if adb_path.exists():
        os.environ['ADB_PATH'] = str(adb_path)
        logger.debug(f"ADB path set to: {adb_path}")
        
        # Check for required DLLs
        required_dlls = ['AdbWinApi.dll', 'AdbWinUsbApi.dll']
        for dll in required_dlls:
            dll_path = resources_dir / dll
            if not dll_path.exists():
                logger.error(f"Required DLL not found: {dll}")
                raise FileNotFoundError(f"Required DLL not found: {dll}")
            logger.debug(f"Found required DLL: {dll}")
    else:
        logger.error(f"ADB executable not found at: {adb_path}")
        raise FileNotFoundError(f"ADB executable not found at: {adb_path}")

def setup_environment():
    """Set up the Windows environment"""
    logger = setup_logging()
    
    # Set required environment variables
    os.environ['PYTHONIOENCODING'] = 'utf-8'
    
    # Add Windows system paths
    system32_path = os.path.join(os.environ['SystemRoot'], 'System32')
    if system32_path not in os.environ['PATH']:
        os.environ['PATH'] = os.pathsep.join([system32_path, os.environ['PATH']])
    
    logger.debug("Windows environment variables set up completed")


def check_system_requirements():
    """Check if all system requirements are met"""
    logger = setup_logging()
    
    # Check Windows version
    try:
        import platform
        windows_version = platform.win32_ver()
        logger.debug(f"Windows version: {windows_version}")
        
        # Check for required Windows features
        try:
            # Import WMI
            import wmi
            w = wmi.WMI()
            logger.debug("WMI access successful")
            
            # Check .NET Framework
            try:
                key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, 
                                   r"SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full",
                                   0, winreg.KEY_READ)
                value, _ = winreg.QueryValueEx(key, "Release")
                winreg.CloseKey(key)
                logger.debug(f".NET Framework version: {value}")
            except WindowsError:
                logger.warning(".NET Framework 4.x not found")
                
        except Exception as e:
            logger.warning(f"Failed to check Windows features: {e}")
            
    except Exception as e:
        logger.error(f"Failed to check Windows version: {e}")


def main():
    """Main entry point for the runtime hook"""
    logger = setup_logging()
    
    try:
        logger.debug("Starting Windows runtime hook initialization")
        
        # Check admin privileges
        if not is_admin():
            logger.warning("Application is not running with administrator privileges")
        
        # Set up basic environment
        setup_environment()
        
        # Set up ADB path
        setup_adb_path()
        
        # Set up USB drivers
        setup_usb_drivers()
        
        # Check system requirements
        check_system_requirements()
        
        logger.debug("Windows runtime hook initialization completed successfully")
    
    except Exception as e:
        logger.error(f"Failed to initialize Windows environment: {e}")
        raise


if __name__ == "__main__":
    main()