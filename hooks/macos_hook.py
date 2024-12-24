import os
import sys
import subprocess
import logging
from pathlib import Path
import stat


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


def setup_adb_path():
    """Set up ADB path for macOS"""
    logger = setup_logging()
    bundle_dir = get_bundle_dir()
    resources_dir = bundle_dir / 'resources'
    
    # Ensure the resources directory exists
    if not resources_dir.exists():
        resources_dir.mkdir(parents=True, exist_ok=True)
    
    # Set ADB path in environment
    adb_path = resources_dir / 'adb'
    if adb_path.exists():
        # Make sure ADB is executable
        current_mode = os.stat(adb_path).st_mode
        os.chmod(adb_path, current_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        
        os.environ['ADB_PATH'] = str(adb_path)
        logger.debug(f"ADB path set to: {adb_path}")
    else:
        logger.error(f"ADB binary not found at: {adb_path}")
        raise FileNotFoundError(f"ADB binary not found at: {adb_path}")

def setup_environment():
    """Set up the macOS environment"""
    logger = setup_logging()
    
    # Set locale settings
    os.environ['LC_ALL'] = 'en_US.UTF-8'
    os.environ['LANG'] = 'en_US.UTF-8'
    
    # Set up PATH to include common binary locations
    paths = [
        '/usr/local/bin',
        '/usr/bin',
        '/bin',
        '/usr/sbin',
        '/sbin'
    ]
    os.environ['PATH'] = os.pathsep.join(paths + [os.environ.get('PATH', '')])
    
    logger.debug("Environment variables set up completed")


def check_system_requirements():
    """Check if all system requirements are met"""
    logger = setup_logging()
    
    required_tools = {
        'openssl': 'brew list openssl >/dev/null 2>&1'
    }
    
    missing_tools = []
    
    for tool, check_cmd in required_tools.items():
        try:
            subprocess.run(check_cmd, shell=True, check=True)
            logger.debug(f"{tool} is installed")
        except subprocess.CalledProcessError:
            missing_tools.append(tool)
            logger.warning(f"{tool} is not installed")
    
    if missing_tools:
        logger.warning(f"Missing required tools: {', '.join(missing_tools)}")
        logger.info("You can install missing tools using: brew install " + " ".join(missing_tools))


def main():
    """Main entry point for the runtime hook"""
    logger = setup_logging()
    
    try:
        logger.debug("Starting macOS runtime hook initialization")
        
        # Set up basic environment
        setup_environment()
        
        # Set up ADB path
        setup_adb_path()
        
        # Check system requirements
        check_system_requirements()
        
        logger.debug("macOS runtime hook initialization completed successfully")
    
    except Exception as e:
        logger.error(f"Failed to initialize macOS environment: {e}")
        raise


if __name__ == "__main__":
    main()
