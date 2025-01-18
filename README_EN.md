# Android TV Time Fixer

**Fixing Time Synchronization Issues on Android TV**

## Problem Description

Many televisions and Android TV boxes, particularly in regions with network restrictions, experience system clock resets after being disconnected from the power supply. Despite having the automatic time synchronization feature enabled, the device fails to connect to a time server, leading to the following consequences:

*   **Loss of access to internet applications:** Many applications require accurate time for proper functioning.
*   **Necessity of manual time setting:** Users have to manually set the time each time after the device is disconnected from power.
*   **"Connected, no internet access" message in Wi-Fi settings:** This indicates that the device is unable to synchronize time with the server.

**Reason:** The primary reason is the inability of the device to connect to the standard Google NTP server (`time.android.com`) due to network restrictions in those regions.

**Solution:** Android TV Time Fixer resolves this problem by replacing the standard Google NTP server with an alternative one available in your region.

## About the Program

**Android TV Time Fixer** is a Windows utility designed to manage NTP server settings on Android TV devices via ADB (Android Debug Bridge). The program performs the following functions:

*   Runs as an executable file (`.exe`) in a Windows environment.
*   Operates through PowerShell.
*   Provides the ability to configure NTP servers by country code or manually.
*   Generates and uses ADB keys for secure TCP connections.

## Key Features

*   **NTP Server Modification:**
    *   Automatic setup by country code.
    *   Manual setup of a custom NTP server.
*   **Viewing Current Device Settings:**
    *   Device model.
    *   Android version.
    *   Serial number.
    *   Currently used NTP server.

## Installation

1.  Download the `AndroidTVTimeFixer-windows.zip` archive from the [Releases](https://github.com/civisrom/android-tv-date-time/releases) section.
2.  Extract the archive to a convenient location on your computer, for example, `D:\AndroidTVTimeFixer`.
3.  Open **PowerShell** as an administrator.
4.  Navigate to the program's folder:
    ```powershell
    cd "D:\AndroidTVTimeFixer"
    ```
5.  Run the program:
    ```powershell
    .\AndroidTVTimeFixer.exe
    ```

## Android TV Setup

### Enabling ADB Debugging (Developer Mode)

1.  On your Android TV, open: **Settings** > **Device Preferences** > **About**.
2.  Click on the **"Build"** item 7 times to unlock developer mode.
3.  Go to: **Device Preferences** > **Developer options**.
4.  Enable **"Network Debugging"**.
5.  Open: **Settings** > **Date & Time**.
6.  Enable: **Auto date & time** > **Use network time**.
7.  For enhanced security, it is recommended to disable developer mode after completing the NTP server configuration.

## Usage

1.  Connect your Android TV (or Nvidia Shield) and your computer to the same local network.
2.  Find your Android TV's IP address in **"Settings > Network & internet"**.
3.  Run the `AndroidTVTimeFixer.exe` program on your computer.
4.  Follow the instructions within the program to connect to your Android TV and configure the NTP server.

## Compatibility

The program has been tested and should work on Android TV devices (including Nvidia Shield) that meet the following requirements:

*   Support for ADB connections over the network.
*   Support for NTP server management via `adb shell` commands.

## Disclaimer

**WARNING: IMPORTANT TO READ BEFORE USING THE PROGRAM**

The **Android TV Time Fixer** program is provided on an **"as is"** basis, without any warranties, express or implied, including but not limited to warranties of merchantability, fitness for a particular purpose, and non-infringement.

**Disclaimer of Liability for Losses:**

The author(s) and developers of the program shall not be liable for any direct, indirect, incidental, special, punitive, or consequential damages, including but not limited to loss of data, loss of profits, business interruption, property damage, or any other damages arising from the use or inability to use this program, even if the author(s) have been advised of the possibility of such damages.

**Disclaimer of Warranties:**

We do not warrant that:

*   The program will meet your requirements.
*   The operation of the program will be uninterrupted and error-free.
*   Any defects in the program will be corrected.
*   The use of the program will not lead to any adverse consequences for your device or network.
*   The program will be compatible with all devices and versions of Android TV.
*   The program will operate correctly in all regions and networks, including regions with network restrictions.

**Agreement to Terms:**

By using the **Android TV Time Fixer** program, you:

*   **Agree to the terms of this disclaimer.**
*   **Assume all risks** associated with the use of the program.
*   **Release the author(s) and developers from any liability** for any losses or damages that may arise from the use of the program.
