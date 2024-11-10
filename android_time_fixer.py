import os
import sys
import re
import time
import logging
import platform
from pathlib import Path
from adb_shell.auth.keygen import keygen
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)

class AndroidTVTimeFixerError(Exception):
    """Base exception class for AndroidTVTimeFixer"""
    pass

class AndroidTVTimeFixer:
    def __init__(self):
        self.current_path = Path.cwd()
        self.keys_folder = self.current_path / 'keys'
        self.device = None
        
    @staticmethod
    def validate_ip(ip: str) -> bool:
        pattern = r'^(\d{1,3}\.){3}\d{1,3}$'
        if not re.match(pattern, ip):
            return False
        octets = ip.split('.')
        return all(0 <= int(octet) <= 255 for octet in octets)

    @staticmethod
    def validate_country_code(code: str) -> bool:
        return bool(re.match(r'^[a-zA-Z]{2}$', code))

    def gen_keys(self) -> None:
        try:
            if not self.keys_folder.exists():
                self.keys_folder.mkdir(parents=True)
                priv_key = self.keys_folder / 'adbkey'
                keygen(str(priv_key))
                logger.info('ADB keys have been generated successfully')
            else:
                logger.info('Using existing ADB keys')
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Failed to generate keys: {str(e)}")

    def load_keys(self):
        try:
            with open(self.keys_folder / 'adbkey.pub', 'rb') as f:
                pub = f.read()
            with open(self.keys_folder / 'adbkey', 'rb') as f:
                priv = f.read()
            return pub, priv
        except FileNotFoundError:
            raise AndroidTVTimeFixerError("ADB keys not found. Please generate them first.")
        except Exception as e:
            raise AndroidTVTimeFixerError(f"Failed to load keys: {str(e)}")

    def connect(self, ip: str, max_retries: int = 3) -> None:
        if not self.validate_ip(ip):
            raise AndroidTVTimeFixerError("Invalid IP address format")

        pub, priv = self.load_keys()
        signer = PythonRSASigner(pub, priv)
        
        for attempt in range(max_retries):
            try:
                self.device = AdbDeviceTcp(ip.strip(), 5555, default_transport_timeout_s=9.)
                self.device.connect(rsa_keys=[signer], auth_timeout_s=0.1)
                logger.info(f'Connected to {ip}:5555 successfully')
                return
            except Exception as e:
                if attempt == max_retries - 1:
                    raise AndroidTVTimeFixerError(
                        f"Failed to connect after {max_retries} attempts.\n"
                        "Please ensure:\n"
                        "1. ADB debugging is enabled on your TV\n"
                        "2. Your TV and PC are on the same network\n"
                        "3. The IP address is correct"
                    )
                logger.warning(f"Connection attempt {attempt + 1} failed, retrying...")
                time.sleep(2)

    def fix_time(self, country_code: str) -> None:
        if not self.device:
            raise AndroidTVTimeFixerError("Not connected to any device")
        
        if not self.validate_country_code(country_code):
            raise AndroidTVTimeFixerError("Invalid country code format. Please use two letters (e.g., 'us', 'uk')")

        try:
            current_ntp = self.device.shell('settings get global ntp_server')
            logger.info(f'Current NTP server: {current_ntp}')

            ntp_server = f'{country_code.strip().lower()}.pool.ntp.org'
            self.device.shell(f'settings put global ntp_server {ntp_server}')
            logger.info(f'NTP server set to {ntp_server}')

            # Verify the change
            new_ntp = self.device.shell('settings get global ntp_server')
            if ntp_server not in new_ntp:
                raise AndroidTVTimeFixerError("Failed to verify NTP server change")

        except Exception as e:
            raise AndroidTVTimeFixerError(f"Failed to update NTP server: {str(e)}")

def main():
    fixer = AndroidTVTimeFixer()
    
    try:
        # Show initial instructions
        print("\nAndroid TV Time & Date Fixer")
        print("\nPlease ensure the following are done:")
        print("1. Enable ADB debugging on your TV:")
        print("   Settings > Device Preferences > About > Build (click 7 times)")
        print("   Then: Developer Options > ADB Debugging")
        print("2. Set Time & Date to automatic:")
        print("   Settings > Device Preferences > Date & Time > Use Network Provided Time")
        print("3. Connect TV and PC to the same network")
        input("\nPress Enter to continue...")

        # Generate ADB keys
        fixer.gen_keys()

        # Get TV IP address
        while True:
            ip = input('\nEnter your TV IP (found in Settings > Network & Internet): ').strip()
            if fixer.validate_ip(ip):
                break
            print("Invalid IP format. Please use format: xxx.xxx.xxx.xxx")

        # Connect to device
        fixer.connect(ip)

        # Get country code and fix time
        while True:
            code = input('\nEnter your country code (e.g., us for USA, uk for UK): ').strip()
            if fixer.validate_country_code(code):
                break
            print("Invalid country code. Please use two letters (e.g., 'us', 'uk')")

        fixer.fix_time(code)

        print("\nTime settings updated successfully!")
        print("Please ensure Time & Date is set to automatic on your TV.")
        print("\nCreated by Jagar Yousef (Rojava Programmers Forum)")
        
    except AndroidTVTimeFixerError as e:
        print(f"\nError: {str(e)}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nOperation cancelled by user")
        sys.exit(0)
    except Exception as e:
        print(f"\nUnexpected error: {str(e)}")
        sys.exit(1)

if __name__ == '__main__':
    main()
