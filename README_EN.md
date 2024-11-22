# Android TV Time Fixer

## Problem Description

Many TVs and devices with Android TV, have an unpleasant feature: the clock resets after disconnecting the device from power. Despite having the "automatic time update" function enabled, time synchronization with the server doesn't occur. This leads to:

- Loss of access to all internet applications
- Need for manual time setting
- "Connected, no internet access" message in Wi-Fi settings

The main reason: TVs cannot connect to Google's server (time.android.com) for time updates due to network restrictions in sanctioned regions. The solution is to replace Google's default NTP server with an alternative.

## About the Program

Android TV Time Fixer is a utility for managing NTP server settings on Android TV devices via ADB. The program:

- Runs on Windows as an .exe file
- Works through PowerShell
- Allows configuring NTP servers by country code or manually

### Key Features

- NTP server modification:
  - By country code
  - Custom server setup
- View current device settings:
  - Model
  - Android version
  - Serial number
  - Current NTP server
- Generation and use of ADB keys for secure TCP connection

## Installation

1. Download `AndroidTVTimeFixer-windows.zip` from releases
2. Extract the archive to a convenient location, e.g., D:\AndroidTVTimeFixer
3. Open PowerShell with administrator privileges
4. Navigate to the program folder:
```powershell
cd "D:\AndroidTVTimeFixer"
```
5. Run the program:
```powershell
.\AndroidTVTimeFixer.exe
```

## Device Preparation

### Enabling ADB Debugging

1. Open: Settings > Device Preferences > About
2. Click 7 times on "Build"
3. Go to: Device Preferences > Developer Options
4. Enable "Network debugging"

### Time Settings

1. Open: Settings > Date & time
2. Enable: Automatic date & time > Use network-provided time

## Usage

1. Connect Android TV or Nvidia Shield and PC to the same network
2. Find the device's IP address in "Settings > Network & Internet"
3. Launch the program and follow the connection instructions

## Compatibility

The program works with Android TV devices (including Nvidia Shield) that must support:
- Network ADB connection
- Time management via adb shell commands

## Disclaimer

**ATTENTION:** Android TV Time Fixer is provided "as is". The author(s) are not responsible for any possible losses or damages resulting from the use of this program.
