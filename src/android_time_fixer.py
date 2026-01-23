"""
AndroidTVTimeFixer - Time synchronization utility for Android TV devices

This application allows you to fix time synchronization issues on Android TV
devices by configuring NTP servers through ADB connection.

Features:
    - Connect to Android TV via ADB over network
    - Set country-specific NTP servers
    - Set custom NTP servers
    - Check NTP server availability
    - Device information display
    - Terminal mode for direct ADB commands

Requirements:
    - Android TV with network ADB enabled
    - Network connectivity between host and device

Author: Orientalium
Version: 1.1.0
"""

import os
import sys
import re
import socket
import shlex
import time
import logging
import platform
import json
import atexit
import signal
import subprocess
from subprocess import Popen, PIPE, TimeoutExpired
from pathlib import Path
from typing import Optional, Tuple, Dict, List, Any

import ntplib
import pyperclip
import colorama
from colorama import Fore, Style, init
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

# Initialize colorama for colored terminal output
init(autoreset=True)

# Platform-specific imports
try:
    import psutil
except ImportError:
    psutil = None

if sys.platform == 'win32':
    try:
        import wmi
    except ImportError:
        wmi = None
else:
    wmi = None

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent))

# Import application modules
from locales import locales, set_language
from constants import (
    DEFAULT_ADB_PORT,
    DEFAULT_CONNECTION_TIMEOUT,
    DEFAULT_RETRY_DELAY,
    MAX_CONNECTION_RETRIES,
    NTP_TIMEOUT,
    NTP_PING_COUNT,
    DEFAULT_ENCODING,
    ADB_KILL_SERVER_TIMEOUT,
    ADB_DISCONNECT_TIMEOUT,
    ADB_PROCESS_WAIT_TIMEOUT,
    LOG_FILENAME,
    SAVED_SERVERS_FILENAME,
    KEYS_FOLDER_NAME,
    ADB_CONNECTION_ERRORS,
    COUNTRY_NTP_SERVERS,
    CUSTOM_NTP_SERVERS
)
from logging_config import setup_logging

# Setup logging
logger = setup_logging(LOG_FILENAME)


class AndroidTVTimeFixerError(Exception):
    """Base exception class for AndroidTVTimeFixer"""
    pass


class ADBProcessManager:
    """
    Manages ADB process lifecycle including cleanup and termination.
    
    This class handles:
        - ADB process discovery and termination
        - Device disconnection
        - Signal handling for graceful shutdown
        - Platform-specific process management
    """
    
    def __init__(self, adb_path: str, device_ip: Optional[str] = None):
        """
        Initialize ADB process manager.
        
        Args:
            adb_path: Full path to ADB executable
            device_ip: IP address of connected device (optional)
        """
        self.adb_path = adb_path
        self.device_ip = device_ip
        self.logger = logging.getLogger(__name__)
        self._cleanup_performed = False
        self._setup_handlers()
    
    def _setup_handlers(self) -> None:
        """Setup exit and signal handlers for cleanup"""
        try:
            atexit.register(self.cleanup)
            
            # Register signal handlers
            for sig in (signal.SIGINT, signal.SIGTERM):
                try:
                    signal.signal(sig, self._signal_handler)
                except (ValueError, OSError) as e:
                    self.logger.debug(f"Cannot register handler for signal {sig}: {e}")
                    
        except Exception as e:
            self.logger.error(f"Error setting up handlers: {e}")
    
    def _signal_handler(self, signum: int, frame: Any) -> None:
        """
        Handle termination signals.
        
        Args:
            signum: Signal number
            frame: Current stack frame
        """
        self.logger.info(f"Received signal {signum}, cleaning up...")
        print(f"\n{Fore.YELLOW}{locales.get('terminal_mode_exit_ctrl_c')}")
        self.cleanup()
        sys.exit(0)
    
    def cleanup(self, force: bool = False) -> None:
        """
        Perform complete cleanup of ADB resources.
        
        Args:
            force: Force cleanup even if already performed
        """
        if self._cleanup_performed and not force:
            return
        
        try:
            # Step 1: Disconnect device (only once!)
            if self.device_ip:
                self._disconnect_device()
            
            # Step 2: Kill ADB server
            self._kill_adb_server()
            
            # Step 3: Terminate remaining processes
            if psutil:
                self._terminate_via_psutil()
            
            # Step 4: Platform-specific termination
            self._terminate_platform_specific()
            
            self._cleanup_performed = True
            self.logger.info("Cleanup completed successfully")
            
        except Exception as e:
            self.logger.error(f"Error during cleanup: {e}", exc_info=True)
    
    def _disconnect_device(self) -> bool:
        """
        Disconnect from ADB device.
        
        Returns:
            True if disconnected successfully, False otherwise
        """
        if not self.device_ip:
            return True
        
        try:
            # Add default port if not specified
            device_address = (
                self.device_ip 
                if ':' in self.device_ip 
                else f"{self.device_ip}:{DEFAULT_ADB_PORT}"
            )
            
            self.logger.info(f"Disconnecting from {device_address}")
            
            result = subprocess.run(
                [self.adb_path, 'disconnect', device_address],
                capture_output=True,
                timeout=ADB_DISCONNECT_TIMEOUT,
                text=True
            )
            
            if result.returncode == 0:
                self.logger.info(f"Successfully disconnected from {device_address}")
                return True
            else:
                self.logger.warning(f"Disconnect failed: {result.stderr.strip()}")
                return False
                
        except TimeoutExpired:
            self.logger.warning("Disconnect operation timed out")
            return False
        except Exception as e:
            self.logger.error(f"Error disconnecting device: {e}")
            return False
    
    def _kill_adb_server(self) -> bool:
        """
        Kill ADB server gracefully.
        
        Returns:
            True if server killed successfully, False otherwise
        """
        try:
            self.logger.info("Executing 'adb kill-server'")
            
            result = subprocess.run(
                [self.adb_path, 'kill-server'],
                capture_output=True,
                timeout=ADB_KILL_SERVER_TIMEOUT,
                text=True
            )
            
            if result.returncode == 0:
                self.logger.info("ADB server killed successfully")
                return True
            else:
                self.logger.warning(f"Kill server failed: {result.stderr.strip()}")
                return False
                
        except TimeoutExpired:
            self.logger.warning("Kill server operation timed out")
            return False
        except Exception as e:
            self.logger.error(f"Error killing ADB server: {e}")
            return False
    
    def _find_adb_processes(self) -> List[Any]:
        """
        Find all ADB processes efficiently.
        
        Returns:
            List of psutil.Process objects representing ADB processes
        """
        if not psutil:
            return []
        
        adb_processes = []
        
        try:
            for proc in psutil.process_iter(['name', 'exe', 'pid']):
                try:
                    proc_name = proc.info['name']
                    if not proc_name:
                        continue
                    
                    # Check by name (case-insensitive)
                    if 'adb' in proc_name.lower():
                        adb_processes.append(proc)
                        continue
                    
                    # Check by executable path
                    proc_exe = proc.info.get('exe')
                    if proc_exe and self.adb_path in proc_exe:
                        adb_processes.append(proc)
                        
                except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                    continue
                    
        except Exception as e:
            self.logger.warning(f"Error finding ADB processes: {e}")
        
        return adb_processes
    
    def _terminate_via_psutil(self) -> int:
        """
        Terminate ADB processes using psutil.
        
        Returns:
            Number of processes terminated
        """
        processes = self._find_adb_processes()
        if not processes:
            self.logger.debug("No ADB processes found via psutil")
            return 0
        
        terminated_count = 0
        
        for proc in processes:
            try:
                self.logger.debug(f"Terminating process PID {proc.pid}")
                proc.terminate()
                
                try:
                    proc.wait(timeout=ADB_PROCESS_WAIT_TIMEOUT)
                    terminated_count += 1
                    self.logger.debug(f"Process PID {proc.pid} terminated gracefully")
                except psutil.TimeoutExpired:
                    self.logger.debug(f"Process PID {proc.pid} did not terminate, killing...")
                    proc.kill()
                    terminated_count += 1
                    self.logger.debug(f"Process PID {proc.pid} killed forcefully")
                    
            except (psutil.NoSuchProcess, psutil.AccessDenied) as e:
                self.logger.debug(f"Cannot terminate process PID {proc.pid}: {e}")
        
        if terminated_count > 0:
            self.logger.info(f"Terminated {terminated_count} ADB process(es)")
        
        return terminated_count
    
    def _terminate_platform_specific(self) -> None:
        """Execute platform-specific termination methods"""
        if sys.platform == 'win32':
            self._terminate_windows()
        else:
            self._terminate_unix()
    
    def _terminate_windows(self) -> None:
        """Windows-specific ADB process termination"""
        try:
            # Method 1: taskkill
            result = subprocess.run(
                ['taskkill', '/F', '/IM', 'adb.exe'],
                capture_output=True,
                timeout=5,
                text=True
            )
            
            if result.returncode == 0:
                self.logger.debug("Executed taskkill for adb.exe")
            
            # Method 2: WMI (if available)
            if wmi:
                self._terminate_via_wmi()
                
        except TimeoutExpired:
            self.logger.debug("Taskkill operation timed out")
        except Exception as e:
            self.logger.debug(f"Windows termination error: {e}")
    
    def _terminate_via_wmi(self) -> None:
        """Terminate ADB processes using Windows WMI"""
        if not wmi:
            return
        
        try:
            c = wmi.WMI()
            
            # Find and terminate by process name
            for process in c.Win32_Process(name='adb.exe'):
                try:
                    process.Terminate()
                    self.logger.debug(f"Terminated ADB process PID {process.ProcessId} via WMI")
                except Exception as e:
                    self.logger.debug(f"WMI termination error for PID {process.ProcessId}: {e}")
            
            # Find and terminate by executable path
            for process in c.Win32_Process(ExecutablePath=self.adb_path):
                try:
                    process.Terminate()
                    self.logger.debug(f"Terminated ADB process PID {process.ProcessId} via WMI (by path)")
                except Exception as e:
                    self.logger.debug(f"WMI path termination error: {e}")
                    
        except Exception as e:
            self.logger.debug(f"WMI error: {e}")
    
    def _terminate_unix(self) -> None:
        """Unix-specific ADB process termination"""
        try:
            result = subprocess.run(
                ['pkill', '-9', 'adb'],
                capture_output=True,
                timeout=5,
                text=True
            )
            
            if result.returncode == 0:
                self.logger.debug("Executed pkill for adb")
                
        except TimeoutExpired:
            self.logger.debug("Pkill operation timed out")
        except Exception as e:
            self.logger.debug(f"Unix termination error: {e}")


class AndroidTVTimeFixer:
    """
    Main application class for Android TV time synchronization.
    
    This class provides functionality to:
        - Connect to Android TV devices via ADB
        - Configure NTP servers
        - Check device information
        - Test NTP server connectivity
        - Execute custom ADB commands
    """
    
    def __init__(self):
        """Initialize AndroidTVTimeFixer application"""
        self.current_path = Path.cwd()
        self.keys_folder = self.current_path / KEYS_FOLDER_NAME
        self.logger = logging.getLogger(__name__)
        
        # ADB configuration
        self._adb_path: Optional[str] = None
        self._adb_path = self.get_adb_path()
        self.process_manager = ADBProcessManager(self._adb_path)
        
        # Device connection
        self.device: Optional[AdbDeviceTcp] = None
        self.max_connection_retries = MAX_CONNECTION_RETRIES
        self.connection_retry_delay = DEFAULT_RETRY_DELAY
        self.connection_timeout = DEFAULT_CONNECTION_TIMEOUT
        
        # Server configuration
        self.servers_file = self.current_path / SAVED_SERVERS_FILENAME
        self.saved_servers = self.load_saved_servers()
        self.ntp_servers = COUNTRY_NTP_SERVERS
        self.custom_ntp_servers = CUSTOM_NTP_SERVERS
    
    def get_adb_path(self) -> str:
        """
        Get path to ADB executable.
        
        Returns:
            Full path to ADB executable
            
        Raises:
            FileNotFoundError: If ADB executable not found
        """
        if self._adb_path and os.path.exists(self._adb_path):
            return self._adb_path
        
        # Determine base path (PyInstaller or development)
        if getattr(sys, 'frozen', False):
            base_path = sys._MEIPASS
        else:
            base_path = os.path.abspath(os.path.dirname(__file__))
        
        # Determine ADB filename
        adb_filename = 'adb.exe' if sys.platform == 'win32' else 'adb'
        
        # Construct path
        self._adb_path = os.path.join(base_path, 'resources', adb_filename)
        
        if not os.path.exists(self._adb_path):
            raise FileNotFoundError(
                f"ADB executable not found at: {self._adb_path}\n"
                f"Please ensure ADB is installed in the resources directory."
            )
        
        self.logger.info(f"Using ADB at: {self._adb_path}")
        return self._adb_path
    
    def gen_keys(self) -> None:
        """
        Generate RSA keys for ADB authentication.
        
        Creates a keys directory and generates adbkey and adbkey.pub
        if they don't already exist.
        """
        try:
            self.keys_folder.mkdir(exist_ok=True)
            
            key_file = self.keys_folder / 'adbkey'
            
            if not key_file.exists():
                self.logger.info("Generating ADB authentication keys...")
                keygen(str(key_file))
                self.logger.info(f"Keys generated successfully at: {key_file}")
                print(f"{Fore.GREEN}ADB keys generated at: {key_file}")
            else:
                self.logger.debug("ADB keys already exist")
                
        except Exception as e:
            error_msg = f"Failed to generate ADB keys: {e}"
            self.logger.error(error_msg)
            raise AndroidTVTimeFixerError(error_msg)
    
    def load_saved_servers(self) -> Dict[str, Any]:
        """
        Load saved server configuration from JSON file.
        
        Returns:
            Dictionary containing saved server configurations
        """
        if not self.servers_file.exists():
            return {}
        
        try:
            with open(self.servers_file, 'r', encoding='utf-8') as f:
                return json.load(f)
        except json.JSONDecodeError as e:
            self.logger.warning(f"Invalid JSON in saved servers file: {e}")
            return {}
        except Exception as e:
            self.logger.error(f"Error loading saved servers: {e}")
            return {}
    
    def save_server(self, ip: str, ntp_server: str) -> None:
        """
        Save server configuration to JSON file.
        
        Args:
            ip: Device IP address
            ntp_server: NTP server address
        """
        try:
            self.saved_servers[ip] = {
                'ntp_server': ntp_server,
                'last_updated': time.strftime('%Y-%m-%d %H:%M:%S')
            }
            
            with open(self.servers_file, 'w', encoding='utf-8') as f:
                json.dump(self.saved_servers, f, indent=2, ensure_ascii=False)
                
            self.logger.info(f"Saved configuration for {ip}")
            
        except Exception as e:
            self.logger.error(f"Error saving server configuration: {e}")
    
    @staticmethod
    def validate_ip(ip: str) -> bool:
        """
        Validate IP address format.
        
        Args:
            ip: IP address string to validate
            
        Returns:
            True if valid IPv4 address, False otherwise
        """
        pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
        if not re.match(pattern, ip):
            return False
        
        # Validate octets
        octets = ip.split('.')
        return all(0 <= int(octet) <= 255 for octet in octets)
    
    @staticmethod
    def validate_country_code(code: str) -> bool:
        """
        Validate country code format.
        
        Args:
            code: Country code string to validate
            
        Returns:
            True if valid 2-letter country code, False otherwise
        """
        return bool(re.match(r'^[a-zA-Z]{2}$', code))
    
    def connect(self, ip: str, port: int = DEFAULT_ADB_PORT) -> None:
        """
        Connect to Android TV device via ADB.
        
        Args:
            ip: Device IP address
            port: ADB port number (default: 5555)
            
        Raises:
            AndroidTVTimeFixerError: If connection fails
        """
        try:
            device_address = f"{ip}:{port}"
            self.logger.info(f"Attempting to connect to {device_address}")
            
            # Execute ADB connect command
            connect_command = f"{self._adb_path} connect {device_address}"
            
            if not self._retry_adb_connection(connect_command):
                raise AndroidTVTimeFixerError(
                    locales.get("connection_failed", ip=device_address)
                )
            
            # Load RSA signer
            key_file = self.keys_folder / 'adbkey'
            
            if not key_file.exists():
                self.gen_keys()
            
            with open(key_file, 'r') as f:
                private_key = f.read()
            
            signer = PythonRSASigner('', private_key)
            
            # Connect using adb-shell
            self.device = AdbDeviceTcp(ip, port, default_transport_timeout_s=9.0)
            self.device.connect(rsa_keys=[signer], auth_timeout_s=10.0)
            
            self.logger.info(f"Successfully connected to {device_address}")
            print(f"{Fore.GREEN}{locales.get('connected_successfully', ip=device_address)}")
            
            # Update process manager with device IP
            self.process_manager.device_ip = device_address
            
        except Exception as e:
            error_msg = f"Connection error: {e}"
            self.logger.error(error_msg)
            raise AndroidTVTimeFixerError(locales.get("connection_error", error=str(e)))
    
    def _extract_device_ip(self, command: str) -> Optional[str]:
        """
        Extract device IP and port from ADB command.
        
        Args:
            command: ADB command string
            
        Returns:
            Device address as "ip:port" or None if not found
        """
        pattern = r'(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(\d+))?'
        match = re.search(pattern, command)
        
        if not match:
            return None
        
        ip = match.group(1)
        port = match.group(2) or str(DEFAULT_ADB_PORT)
        
        return f"{ip}:{port}"
    
    def _should_reset_server(self, attempt: int) -> bool:
        """
        Determine if ADB server should be reset on this attempt.
        
        Args:
            attempt: Current attempt number (0-indexed)
            
        Returns:
            True if server should be reset, False otherwise
        """
        # Reset server on attempts 3, 4, and 5 (0-indexed: 2, 3, 4)
        return attempt >= 2
    
    def _reset_adb_server(self, device_ip: Optional[str]) -> None:
        """
        Reset ADB server by killing and disconnecting.
        
        Args:
            device_ip: Device to disconnect (optional)
        """
        try:
            # Kill ADB server
            self.logger.info("Resetting ADB server...")
            result = subprocess.run(
                [self._adb_path, 'kill-server'],
                capture_output=True,
                timeout=ADB_KILL_SERVER_TIMEOUT,
                text=True
            )
            
            if result.returncode == 0:
                self.logger.debug("ADB server killed successfully")
            else:
                self.logger.warning(f"Kill server failed: {result.stderr.strip()}")
            
            # Disconnect specific device
            if device_ip:
                result = subprocess.run(
                    [self._adb_path, 'disconnect', device_ip],
                    capture_output=True,
                    timeout=ADB_DISCONNECT_TIMEOUT,
                    text=True
                )
                
                if result.returncode == 0:
                    self.logger.debug(f"Disconnected from {device_ip}")
                else:
                    self.logger.warning(f"Disconnect failed: {result.stderr.strip()}")
                    
        except Exception as e:
            self.logger.warning(f"Error resetting ADB server: {e}")
    
    def _execute_adb_command(self, command: str) -> Tuple[int, str, str]:
        """
        Execute ADB command and return result.
        
        Args:
            command: Command string to execute
            
        Returns:
            Tuple of (return_code, stdout, stderr)
        """
        args = shlex.split(command)
        if not args:
            return (1, "", "Empty command")
        
        # Replace 'adb' with full path
        if args[0] == 'adb':
            args[0] = self._adb_path
        
        try:
            process = Popen(
                args,
                stdout=PIPE,
                stderr=PIPE,
                universal_newlines=True,
                encoding=DEFAULT_ENCODING,
                bufsize=1
            )
            
            stdout_lines = []
            while True:
                output = process.stdout.readline()
                if output == '' and process.poll() is not None:
                    break
                if output:
                    clean_output = output.strip()
                    stdout_lines.append(clean_output)
                    print(f"{Fore.GREEN}{clean_output}")
            
            return_code = process.poll()
            _, stderr = process.communicate(timeout=5)
            
            return (return_code, '\n'.join(stdout_lines), stderr)
            
        except TimeoutExpired:
            process.kill()
            return (1, "", "Command timeout")
        except Exception as e:
            return (1, "", str(e))

    
    def _retry_adb_connection(
        self,
        command: str,
        max_retries: int = MAX_CONNECTION_RETRIES,
        delay: int = DEFAULT_RETRY_DELAY
    ) -> bool:
        """
        Retry ADB connection with server reset on later attempts.
        
        Args:
            command: ADB command to execute
            max_retries: Maximum number of attempts
            delay: Delay between retries in seconds
            
        Returns:
            True if connection successful, False otherwise
        """
        device_ip = self._extract_device_ip(command)
        
        for attempt in range(max_retries):
            # Reset server on attempts 3-5
            if self._should_reset_server(attempt):
                self._reset_adb_server(device_ip)
            
            # Try to connect
            return_code, stdout, stderr = self._execute_adb_command(command)
            
            # Check if successful
            if return_code == 0:
                self.logger.info(f"Connection successful on attempt {attempt + 1}")
                return True
            
            # Check for connection errors
            error_msg = stderr.lower() if stderr else ""
            is_connection_error = any(err in error_msg for err in ADB_CONNECTION_ERRORS)
            
            if is_connection_error and attempt < max_retries - 1:
                self.logger.warning(
                    f"Attempt {attempt + 1} failed. Retrying in {delay}s..."
                )
                print(f"{Fore.YELLOW}Connection attempt {attempt + 1} failed. Retrying in {delay}s...")
                time.sleep(delay)
                continue
            else:
                # Non-connection error or last attempt
                if stderr:
                    self.logger.error(f"Command failed: {stderr.strip()}")
                    print(f"{Fore.RED}{stderr.strip()}")
                return False
        
        self.logger.error("All connection attempts exhausted")
        print(f"{Fore.RED}All connection attempts failed")
        return False
    
    def execute_terminal_command(self, command: str) -> None:
        """
        Execute command in terminal mode.
        
        Args:
            command: Command string to execute
        """
        if not command:
            return
        
        try:
            if 'adb' in command:
                # Use retry logic for ADB commands
                self._retry_adb_connection(command)
            else:
                # Execute non-ADB commands directly
                args = shlex.split(command)
                if not args:
                    return
                
                self.logger.debug(f"Executing command: {' '.join(args)}")
                
                return_code, stdout, stderr = self._execute_adb_command(command)
                
                if return_code != 0:
                    self.logger.error(f"Command failed with code {return_code}")
                    print(f"{Fore.RED}{locales.get('command_error')}")
                    if stderr:
                        print(f"{Fore.RED}{stderr}")
                        
        except FileNotFoundError as e:
            error_msg = f"Command not found: {e}"
            self.logger.error(error_msg)
            print(f"{Fore.RED}{locales.get('command_execution_error', error=error_msg)}")
        except Exception as e:
            error_msg = f"Command execution error: {e}"
            self.logger.error(error_msg, exc_info=True)
            print(f"{Fore.RED}{locales.get('command_execution_error', error=error_msg)}")
    
    def terminal_mode(self) -> None:
        """
        Interactive terminal mode for executing ADB commands.
        
        Provides a command-line interface for direct ADB interaction.
        Special commands:
            - exit, quit, q: Exit terminal mode
            - help, ?: Show help
            - clear: Clear screen
        """
        if sys.platform == 'win32':
            os.system('chcp 65001 > nul')  # Set UTF-8 encoding
        
        self.logger.info("Terminal mode started")
        print(f"{Fore.GREEN}{locales.get('terminal_mode_welcome')}")
        print(f"{Fore.YELLOW}{locales.get('terminal_mode_help')}")
        
        try:
            while True:
                try:
                    command = input(f"{Fore.CYAN}terminal> {Fore.WHITE}").strip()
                    
                    # Handle special commands
                    if command.lower() in ('exit', 'quit', 'q'):
                        self.logger.info("Exiting terminal mode")
                        break
                    elif command.lower() in ('help', '?'):
                        print(f"{Fore.YELLOW}{locales.get('terminal_mode_commands')}")
                        continue
                    elif command.lower() == 'clear':
                        os.system('cls' if platform.system() == 'Windows' else 'clear')
                        continue
                    elif not command:
                        continue
                    
                    # Execute command
                    self.execute_terminal_command(command)
                    
                except KeyboardInterrupt:
                    self.logger.info("Keyboard interrupt in terminal mode")
                    print(f"\n{Fore.YELLOW}{locales.get('terminal_mode_exit_ctrl_c')}")
                    continue
                except Exception as e:
                    self.logger.error(f"Error in terminal mode: {e}", exc_info=True)
                    print(f"{Fore.RED}{locales.get('terminal_mode_error', error=str(e))}")
        
        except Exception as e:
            self.logger.error(f"Critical error in terminal mode: {e}", exc_info=True)
            print(f"{Fore.RED}{locales.get('terminal_mode_critical_error', error=str(e))}")
        finally:
            self.process_manager.cleanup()
    
    def get_current_ntp(self) -> Optional[str]:
        """
        Get current NTP server from device.
        
        Returns:
            NTP server address or None if unable to retrieve
            
        Raises:
            AndroidTVTimeFixerError: If device not connected
        """
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            ntp_server = self.device.shell('settings get global ntp_server').strip()
            self.logger.info(f"Current NTP server: {ntp_server}")
            return ntp_server if ntp_server else None
        except Exception as e:
            error_msg = f"Failed to get NTP server: {e}"
            self.logger.error(error_msg)
            raise AndroidTVTimeFixerError(error_msg)
    
    def fix_time(self, ntp_server: str) -> bool:
        """
        Set NTP server on device.
        
        Args:
            ntp_server: NTP server address
            
        Returns:
            True if successful, False otherwise
            
        Raises:
            AndroidTVTimeFixerError: If device not connected or operation fails
        """
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            self.logger.info(f"Setting NTP server to: {ntp_server}")
            
            # Set NTP server
            result = self.device.shell(f'settings put global ntp_server {ntp_server}')
            
            # Verify setting was applied
            time.sleep(1)
            current_ntp = self.get_current_ntp()
            
            if current_ntp == ntp_server:
                self.logger.info(f"NTP server set successfully to {ntp_server}")
                print(f"{Fore.GREEN}{locales.get('ntp_server_set', ntp_server=ntp_server)}")
                return True
            else:
                self.logger.warning(
                    f"NTP server mismatch. Expected: {ntp_server}, Got: {current_ntp}"
                )
                print(f"{Fore.YELLOW}Warning: NTP server may not have been set correctly")
                return False
                
        except Exception as e:
            error_msg = f"Failed to set NTP server: {e}"
            self.logger.error(error_msg)
            raise AndroidTVTimeFixerError(error_msg)
    
    def set_custom_ntp(self) -> None:
        """
        Interactive method to set custom NTP server.
        
        Prompts user for NTP server address and sets it on device.
        """
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            # Show available custom servers
            print(f"{Fore.GREEN}{locales.get('available_custom_servers')}")
            for idx, server in enumerate(self.custom_ntp_servers, 1):
                print(f"{Fore.YELLOW}{idx}. {server}")
            
            # Get user input
            print(f"{Fore.GREEN}{locales.get('enter_custom_ntp')}", end="")
            ntp_input = input(f"{Fore.WHITE}").strip()
            
            # Check if user selected from list
            if ntp_input.isdigit():
                idx = int(ntp_input) - 1
                if 0 <= idx < len(self.custom_ntp_servers):
                    ntp_server = self.custom_ntp_servers[idx]
                else:
                    print(f"{Fore.RED}{locales.get('invalid_selection')}")
                    return
            else:
                ntp_server = ntp_input
            
            # Validate NTP server format (basic check)
            if not ntp_server or len(ntp_server) < 5:
                print(f"{Fore.RED}{locales.get('invalid_ntp_server')}")
                return
            
            # Set NTP server
            self.fix_time(ntp_server)
            
        except Exception as e:
            error_msg = f"Error setting custom NTP: {e}"
            self.logger.error(error_msg)
            print(f"{Fore.RED}{locales.get('error_message', error=error_msg)}")
    
    def show_country_codes(self) -> None:
        """Display available country codes and their NTP servers"""
        print(f"{Fore.GREEN}{locales.get('available_country_codes')}")
        
        # Group by region for better display
        codes = sorted(self.ntp_servers.keys())
        
        for idx in range(0, len(codes), 4):
            line_codes = codes[idx:idx+4]
            line_str = "  ".join(f"{code.upper():3}" for code in line_codes)
            print(f"{Fore.YELLOW}{line_str}")
        
        print(f"\n{Fore.CYAN}{locales.get('country_code_example')}")
    
    def show_custom_ntp_servers(self) -> None:
        """Display available custom NTP servers"""
        print(f"{Fore.GREEN}{locales.get('custom_ntp_servers')}")
        
        for idx, server in enumerate(self.custom_ntp_servers, 1):
            print(f"{Fore.YELLOW}{idx:2}. {server}")

    
    def ping_ntp_servers(
        self,
        timeout: int = NTP_TIMEOUT,
        count: int = NTP_PING_COUNT
    ) -> None:
        """
        Check NTP servers availability and response time.
        
        Args:
            timeout: Timeout for NTP connection in seconds
            count: Number of attempts per server
        """
        print(f"{Fore.GREEN}{locales.get('ping_ntp_servers_start')}")
        
        # Combine all servers
        all_servers = list(self.ntp_servers.values()) + self.custom_ntp_servers
        
        # Remove duplicates while preserving order
        seen = set()
        unique_servers = []
        for server in all_servers:
            if server not in seen:
                seen.add(server)
                unique_servers.append(server)
        
        successful = 0
        failed = 0
        
        for server in unique_servers:
            rtts = []
            errors = []
            
            for attempt in range(count):
                try:
                    ntp_client = ntplib.NTPClient()
                    start_time = time.time()
                    ntp_response = ntp_client.request(server, version=3, timeout=timeout)
                    end_time = time.time()
                    
                    rtt = (end_time - start_time) * 1000  # Convert to milliseconds
                    rtts.append(rtt)
                    
                except ntplib.NTPException as e:
                    errors.append(f"NTP Error: {e}")
                except socket.gaierror:
                    errors.append("DNS resolution failed")
                except socket.timeout:
                    errors.append("Timeout")
                except Exception as e:
                    errors.append(f"Error: {e}")
            
            # Display result
            if rtts:
                avg_rtt = sum(rtts) / len(rtts)
                min_rtt = min(rtts)
                max_rtt = max(rtts)
                
                print(
                    f"{Fore.GREEN}✓ {server:30} "
                    f"Avg: {avg_rtt:6.1f}ms  "
                    f"Min: {min_rtt:6.1f}ms  "
                    f"Max: {max_rtt:6.1f}ms  "
                    f"Success: {len(rtts)}/{count}"
                )
                successful += 1
            else:
                # All attempts failed
                error_summary = errors[0] if errors else "Unknown error"
                print(f"{Fore.RED}✗ {server:30} {error_summary}")
                failed += 1
        
        # Summary
        print(f"\n{Fore.CYAN}Summary:")
        print(f"{Fore.GREEN}  Successful: {successful}")
        print(f"{Fore.RED}  Failed: {failed}")
        print(f"{Fore.YELLOW}  Total: {len(unique_servers)}")
    
    def get_device_info(self) -> Dict[str, str]:
        """
        Get device information.
        
        Returns:
            Dictionary containing device properties
            
        Raises:
            AndroidTVTimeFixerError: If device not connected or error occurs
        """
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            device_info = {
                'manufacturer': self.device.shell('getprop ro.product.manufacturer').strip(),
                'model': self.device.shell('getprop ro.product.model').strip(),
                'android_version': self.device.shell('getprop ro.build.version.release').strip(),
                'sdk_version': self.device.shell('getprop ro.build.version.sdk').strip(),
                'serial': self.device.shell('getprop ro.serialno').strip(),
                'build_id': self.device.shell('getprop ro.build.id').strip(),
                'kernel_version': self.device.shell('uname -r').strip(),
                'secure_boot': self.device.shell('getprop ro.boot.secureboot').strip()
            }
            
            self.logger.debug(f"Device info retrieved: {device_info}")
            return device_info
            
        except Exception as e:
            error_msg = f"Failed to get device info: {e}"
            self.logger.error(error_msg)
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))
    
    def show_current_settings(self) -> None:
        """Display only current NTP server"""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            current_ntp = self.get_current_ntp()
            if current_ntp:
                print(f"{Fore.GREEN}{locales.get('current_ntp_server')} {Fore.RED}{current_ntp}")
            else:
                print(f"{Fore.YELLOW}NTP server not configured")
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("ntp_server_info_error", error=str(e)))
    
    def show_device_info(self) -> None:
        """Display complete device information including NTP server"""
        if not self.device:
            raise AndroidTVTimeFixerError(locales.get("no_device_connected"))
        
        try:
            # Get current NTP
            current_ntp = self.get_current_ntp()
            
            # Get device info
            device_info = self.get_device_info()
            
            # Display information
            print(f"{Fore.GREEN}{locales.get('current_device_info')}")
            print(f"{Fore.GREEN}{locales.get('current_ntp_server')} {Fore.RED}{current_ntp or 'Not configured'}")
            print(f"{Fore.YELLOW}{locales.get('device_info')}")
            
            for key, value in device_info.items():
                formatted_key = key.replace('_', ' ').title()
                print(f"  {Fore.CYAN}{formatted_key:20}: {Fore.WHITE}{value}")
                
        except Exception as e:
            raise AndroidTVTimeFixerError(locales.get("device_info_error", error=str(e)))


def main() -> None:
    """Main application entry point"""
    fixer = AndroidTVTimeFixer()
    
    # Language selection
    print(locales.get("select_language"))
    print(f"1. {locales.get('english')}")
    print(f"2. {locales.get('russian')}")
    
    lang_choice = input(locales.get("enter_number")).strip()
    
    if lang_choice == "2":
        set_language("ru")
        print(locales.get("language_set_ru"))
    else:
        set_language("en")
        print(locales.get("language_set_en"))
    
    try:
        # Show initial instructions
        print(f"{Fore.GREEN}{locales.get('program_title')}")
        print(f"{Fore.WHITE}{locales.get('please_ensure')}")
        print(f"{Fore.YELLOW}{locales.get('adb_setup')}")
        print(f"{Fore.YELLOW}{locales.get('adb_steps')}")
        print(f"{Fore.YELLOW}{locales.get('adb_network')}")
        print(f"{Fore.YELLOW}{locales.get('auto_time_date')}")
        print(f"{Fore.YELLOW}{locales.get('network_requirement')}")
        input(f"{Fore.WHITE}{locales.get('press_enter_to_continue')}")
        
        # Generate ADB keys
        fixer.gen_keys()
        
        # Main menu loop
        while True:
            print(f"\n{Fore.GREEN}{locales.get('main_menu')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_1')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_2')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_3')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_4')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_5')}")
            print(f"{Fore.YELLOW}{locales.get('ping_servers')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_8')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_9')}")
            print(f"{Fore.YELLOW}{locales.get('menu_item_10')}")
            
            choice = input(f"{Fore.GREEN}{locales.get('menu_prompt')}").strip()
            
            if choice == '1':
                # Connect and set country-based NTP
                print(f"{Fore.GREEN}{locales.get('enter_device_ip')}", end="")
                ip = input(f"{Fore.WHITE}").strip()
                
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect(ip)
                        fixer.show_current_settings()
                        
                        print(f"{Fore.GREEN}{locales.get('enter_country_code')}", end="")
                        code = input(f"{Fore.WHITE}").strip()
                        
                        if fixer.validate_country_code(code):
                            code_lower = code.lower()
                            if code_lower in fixer.ntp_servers:
                                ntp_server = fixer.ntp_servers[code_lower]
                                fixer.fix_time(ntp_server)
                                print(f"{Fore.GREEN}{locales.get('ntp_server_set', ntp_server=ntp_server)}")
                            else:
                                print(f"{Fore.RED}{locales.get('invalid_country_code')}")
                        else:
                            print(f"{Fore.RED}{locales.get('invalid_country_code')}")
                            
                    except AndroidTVTimeFixerError as e:
                        print(f"{Fore.RED}{locales.get('error_message', error=str(e))}")
                else:
                    print(f"{Fore.RED}{locales.get('invalid_ip_format')}")
            
            elif choice == '2':
                # Connect and set custom NTP
                print(f"{Fore.GREEN}{locales.get('enter_device_ip')}", end="")
                ip = input(f"{Fore.WHITE}").strip()
                
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect(ip)
                        fixer.show_current_settings()
                        fixer.set_custom_ntp()
                    except AndroidTVTimeFixerError as e:
                        print(f"{Fore.RED}{locales.get('error_message', error=str(e))}")
                else:
                    print(f"{Fore.RED}{locales.get('invalid_ip_format')}")
            
            elif choice == '3':
                # Show country codes
                fixer.show_country_codes()
            
            elif choice == '4':
                # Show custom NTP servers
                fixer.show_custom_ntp_servers()
            
            elif choice == '5':
                # Show device info
                print(f"{Fore.GREEN}{locales.get('enter_device_ip')}", end="")
                ip = input(f"{Fore.WHITE}").strip()
                
                if fixer.validate_ip(ip):
                    try:
                        fixer.connect(ip)
                        fixer.show_device_info()
                    except AndroidTVTimeFixerError as e:
                        print(f"{Fore.RED}{locales.get('error_message', error=str(e))}")
                else:
                    print(f"{Fore.RED}{locales.get('invalid_ip_format')}")
            
            elif choice == '6':
                # Ping NTP servers
                fixer.ping_ntp_servers()
            
            elif choice == '7':
                # Show country codes (alternate menu option)
                print(f"{Fore.GREEN}{locales.get('country_codes_description')}")
                print(locales.get('country_codes'))
            
            elif choice == '8':
                # Terminal mode
                fixer.terminal_mode()
            
            elif choice == '9':
                # Exit
                print(f"{Fore.GREEN}{locales.get('exit_message')}")
                break
            
            elif choice.lower() == 'b':
                # Back (continue to main menu)
                continue
            
            else:
                print(f"{Fore.RED}{locales.get('invalid_choice')}")
    
    except AndroidTVTimeFixerError as e:
        print(f"{Fore.RED}{locales.get('error_message', error=str(e))}")
        logger.error(f"Application error: {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        print(f"\n{Fore.RED}{locales.get('operation_aborted')}")
        sys.exit(0)
    except Exception as e:
        print(f"{Fore.RED}{locales.get('unexpected_error', error=str(e))}")
        logger.error(f"Unexpected error: {e}", exc_info=True)
        sys.exit(1)
    finally:
        # Ensure cleanup
        if 'fixer' in locals():
            fixer.process_manager.cleanup()


if __name__ == '__main__':
    main()
