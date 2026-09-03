[Русский](https://github.com/civisrom/android-tv-date-time/blob/main/README.md)

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

**Android TV Time Fixer** is a cross-platform utility for Windows, Linux, and macOS, designed to manage NTP server settings on Android TV devices via ADB (Android Debug Bridge).

As of version 2.5.0 the project has two halves:

*   **The desktop program** — the full-featured console utility described
    below. Windows, Linux, macOS.
*   **The Android application (APK, experimental)** — the same thing from a
    phone or tablet, with no computer involved at all. See
    [Android application](#android-application).

## Screenshots

![Main Menu](screenshots/en.png)

## Key Features

*   **Multilingual Interface:**
    *   Support for English and Russian languages
    *   Language selection at program startup
    *   Automatic saving and loading of selected language

*   **NTP Server Modification:**
    *   Automatic setup by country code (65+ countries)
    *   Manual setup of a custom NTP server
    *   Input validation (domain names and IP addresses)
    *   NTP server availability check before applying
    *   Interactive hints and country name search

*   **Information Display:**
    *   List of available country codes with names and NTP servers
    *   List of alternative NTP servers (regional pools, Cloudflare, Google, etc.)
    *   Interactive search by code or country name

*   **Detailed Device Information:**
    *   Model and manufacturer
    *   Android version and API level
    *   Serial number
    *   CPU architecture and core count
    *   RAM capacity
    *   Screen resolution and density
    *   Network parameters (IP, MAC address)
    *   Current NTP server
    *   Battery status, timezone, locale
    *   Device uptime
    *   Comparison of device time vs PC time

*   **Server Management:**
    *   Favorite servers (add, remove, view)
    *   Copy/paste servers to/from clipboard
    *   Ping all available NTP servers (110+)
    *   Display response time (RTT)
    *   Success rate percentage
    *   Sorting by availability and speed
    *   Export/import settings to JSON

*   **Android 11+ wireless debugging (new):**
    *   Pair with a device using its 6-digit code, straight from the program
    *   mDNS discovery — you need neither the IP address nor the port
    *   Automatic protocol detection: the program works out on its own whether a
        device speaks the old network debugging or the new encrypted one
    *   Works with Google TV Streamer and Chromecast with Google TV on Android 14,
        where the old network debugging is no longer offered
    *   A private ADB server, so the program never disturbs your `adb` or Android Studio
    *   **Linux and macOS:** ADB keys and the list of paired devices are kept
        next to the program, so your `~/.android` is left alone (on Windows
        `adb` resolves the profile through a system API and does not support
        that isolation)

*   **Network Scan & Batch Operations:**
    *   Automatic local network scanning for Android TV devices
    *   Configurable ADB scan port (5555 by default)
    *   Connect to discovered devices
    *   Batch NTP server update across multiple devices
    *   Device time vs PC time comparison (sync status)

*   **Auto-setup NTP (Experimental Mode):**
    *   Full automation: network scan → connect → select best NTP → install
    *   Quick test of all NTP servers with optimal selection by RTT
    *   Top-5 fastest servers with choice option

*   **Terminal Mode:**
    *   Execute any ADB commands
    *   Execute system commands
    *   Built-in ADB command reference
    *   App management, file operations, device reboot

*   **Android application (new, experimental):**
    *   A separate APK: change the NTP server straight from a phone, no computer
    *   One file for two scenarios — from a phone to the TV over the network,
        or installed on the Android TV itself
    *   Pairing with a 6-digit code for Android 11+ wireless debugging
    *   mDNS discovery on the local network: no need to know the IP or the port
    *   The same NTP server reference as the desktop version — both are
        generated from one shared file, so they cannot drift apart:
        77 countries and 45 alternative servers
    *   Servers are verified **as time servers**, not merely as reachable
        hosts: the app sends a real SNTP request and inspects the reply
    *   Finds the fastest server by testing the whole reference list
    *   An address can be a domain name or an IP
    *   The result is verified by reading it back, not taken from the exit code
        of `settings put`
    *   Device details: model, Android version, memory, screen, uptime and more
    *   Interface in Russian and English, following the system language

*   **Additional Features:**
    *   Save last used IP address
    *   Copy servers to clipboard
    *   Automatic ADB key generation
    *   Connection reuse for existing connections
    *   Detailed file logging
    *   Firewall permission notice

## Installation

### Windows

1.  Download the `AndroidTVTimeFixer-windows.zip` archive from the [Releases](https://github.com/civisrom/android-tv-date-time/releases) section.
2.  Extract the archive to a convenient location on your computer, for example, `D:\AndroidTVTimeFixer`.
3.  Run `AndroidTVTimeFixer.exe` or use `start.bat` / `start.ps1`.

Run via PowerShell

1.  Open **PowerShell** as an administrator.
2.  Navigate to the program's folder:
    ```powershell
    cd "D:\AndroidTVTimeFixer"
    ```
3.  Run the program:
    ```powershell
    .\AndroidTVTimeFixer.exe
    ```

### Linux

1.  Download the `AndroidTVTimeFixer-linux.zip` archive from the [Releases](https://github.com/civisrom/android-tv-date-time/releases) section.
2.  Extract the archive:
    ```bash
    unzip AndroidTVTimeFixer-linux.zip
    ```
3.  Make the file executable and run:
    ```bash
    chmod +x AndroidTVTimeFixer
    ./AndroidTVTimeFixer
    ```

### macOS

1.  Download the `AndroidTVTimeFixer-macos.zip` archive from the [Releases](https://github.com/civisrom/android-tv-date-time/releases) section.
2.  Extract the archive and run the application.

### Android (APK)

1.  Download `AndroidTVTimeFixer-2.5.0.apk` from [Releases](https://github.com/civisrom/android-tv-date-time/releases).
2.  Verify it against the `.apk.sha256` file next to it:
    ```bash
    sha256sum -c AndroidTVTimeFixer-2.5.0.apk.sha256
    ```
3.  Install it:
    *   **On a phone** — open the file and allow installation from unknown
        sources for your file manager or browser.
    *   **On the Android TV itself** — either `adb install AndroidTVTimeFixer-2.5.0.apk`
        from a computer, or any file manager on the TV. The icon appears both in
        the regular launcher and in the Android TV launcher.

Requires **Android 6.0** or newer. See
[Android application](#android-application) for details.

**About the Google Play Protect warning.** Installing an APK from outside
Google Play makes the system show "App blocked to protect your device", saying
Play Protect has never seen apps from this developer. That is expected for
**any** third-party APK and not a sign of trouble: this app is not published on
Google Play. Choose "Install anyway". The file's authenticity is established by
the checksum in step 2 and by the APK signature.

### Application data

The program is portable: ADB keys, settings, and the log live **next to the executable**
(`keys/`, `adb/`, `settings.json`, `saved_servers.json`, `android_tv_fixer.log`).

- `keys/` — the key for direct "network debugging" connections (legacy ADB protocol).
- `adb/` — a private `adb` home for Android 11+ wireless debugging: it holds the key
  and the list of paired devices. On the first run, existing keys from `~/.android` are
  copied here so that already paired devices keep working. **Linux and macOS only:** on
  Windows `adb` resolves the user profile through a system API and ignores environment
  variables, so its keys stay in `%USERPROFILE%\.android` there (verified against
  platform-tools 37.0.1).

The program uses its own ADB server port on **every** platform, so it never interferes
with your `adb` or Android Studio and never kills their sessions.

When the program folder is not writable (installed under `Program Files`, or launched
straight from an archive), the data falls back to the user data directory:

- Windows: `%LOCALAPPDATA%\AndroidTVTimeFixer`
- Linux: `$XDG_DATA_HOME/AndroidTVTimeFixer` or `~/.local/share/AndroidTVTimeFixer`
- macOS: `~/Library/Application Support/AndroidTVTimeFixer`

In that case, existing settings and ADB keys from the application folder are copied
automatically on first launch.

## Android TV Setup

### Step 1. Enable developer mode

1.  On your Android TV, open: **Settings** > **Device Preferences** > **About**.
2.  Click on the **"Build"** item 7 times to unlock developer mode.
3.  Go to: **Device Preferences** > **Developer options**.

### Step 2. Enable debugging — one of two ways

Look for **one** of these entries under Developer options. Which one you get
depends on the firmware, not on the Android version.

**Option A — "Network debugging" (classic).** Present on most TVs and boxes:
Xiaomi, TCL, Nvidia Shield, Fire TV. Just turn the switch on. The device starts
listening on port **5555**, and the program only needs its IP address.

**Option B — "Wireless debugging" (Android 11+).** The only option on Google TV
Streamer and Chromecast with Google TV after the Android 14 update. Turn the
switch on and **leave the screen open** — the ports shown there are random and
change. Then use **item 11** of the main menu (see the "Wireless debugging"
section below).

> The program works out which one you have by itself: enter an address and it
> will tell you whether a pairing code is needed.

### Step 3. Allow automatic time synchronisation

1.  Open: **Settings** > **Date & Time**.
2.  Enable: **Auto date & time** > **Use network time**.

Without this the device will not contact the NTP server you set.

### Step 4. After the setup

For enhanced security, it is recommended to disable developer mode once the NTP
server is configured. The NTP server you set is stored in the system and
survives reboots — debugging is only needed while you configure it.

## Main Menu

```
 1. Change NTP time server by country code
 2. Change NTP time server to custom
 3. Show country codes with country names and NTP servers (can be copied to clipboard)
 4. Show available alternative NTP servers (can be copied to clipboard)
 5. Show current device information
 6. Ping NTP servers
 7. Server management
 8. Network scan & batch NTP update
 9. Auto-setup NTP server (experimental mode)
10. Terminal mode (ADB and system commands)
11. Android 11+ wireless debugging (pairing and mDNS discovery)
 0. Exit
```

### Server Management Submenu

```
1. Show favorite servers
2. Add current server to favorites
3. Copy server to clipboard
4. Paste server from clipboard
5. Remove server from favorites
6. Ping NTP servers
7. Export / Import settings
8. Return to main menu
```

### Wireless Debugging Submenu

```
1. Pair a device with a code
2. Find devices via mDNS
3. Return to main menu
```

### Network Scan Submenu

```
1. Scan local network for Android TV devices
2. Connect to discovered device
3. Batch NTP update (all discovered or entered IPs)
4. Show device time sync status
5. Back to main menu
```

### Export / Import Settings Submenu

```
1. Export settings to file
2. Import settings from file
3. Back
```

## How to Use the Program

### Item 1 — Change NTP time server by country code

The program asks for the IP address of your device (TV or set-top box), connects to it via ADB, shows current settings, and prompts you to enter a two-letter country code (e.g., `ru`, `ua`, `de`). You can type `?name` to search for a country by name. After selecting a code, the program will automatically set the NTP server for that region.

> **Tip:** If you don't know your country code, first open **item 3** — it displays a full list of country codes with names and NTP servers. Copy the desired code to the clipboard and paste it when prompted in item 1.

### Item 2 — Change NTP time server to custom

Similar to item 1, but instead of a country code you enter an NTP server address manually (domain name or IP address). The program will validate the format and set the specified server on the device.

> **Tip:** You can get the NTP server address from **item 3** (servers by country) or **item 4** (alternative servers — Cloudflare, Google, etc.). Open the desired item, copy the server address to the clipboard, and paste it when prompted in item 2.

### Item 3 — Show country codes with country names and NTP servers

Displays a complete list of supported country codes (65+), their names, and corresponding NTP servers. Interactive search is available. Results can be copied to the clipboard.

> **Using the results:** The copied country code can be pasted into **item 1**, and the copied NTP server address can be pasted into **item 2** for manual setup.

### Item 4 — Show available alternative NTP servers

Shows a list of alternative NTP servers: regional pools, Cloudflare, Google, and others. Results can be copied to the clipboard.

> **Using the results:** The copied server address can be pasted into **item 2** for manual installation on the device.

### Item 5 — Show current device information

Connects to the device and displays detailed information: model, manufacturer, Android version, serial number, CPU, RAM, screen resolution, network parameters (IP, MAC), current NTP server, timezone, uptime, and a comparison of device time vs PC time.

### Item 6 — Ping NTP servers

Tests availability of all known NTP servers (110+). Shows response time (RTT), connection success rate, and sorts results by availability and speed.

### Item 7 — Server management

Opens a submenu for managing favorite servers:
- **Show favorites** — list of saved servers
- **Add current server** — saves the current NTP server from the device to favorites
- **Copy server** — copies the current device server to clipboard
- **Paste server** — sets the server from clipboard on the device
- **Remove server** — removes a selected server from the favorites list
- **Export / Import settings** — save and restore all settings (language, IP, favorite servers) to a JSON file

### Item 8 — Network scan & batch NTP update

Opens a submenu for working with multiple devices:
- **Scan network** — first asks for the ADB port (Enter for 5555; the value is remembered), then discovers Android TV devices on the local network. Results are shown as `IP:port`, so a non-standard port is carried over automatically
- **Connect to discovered device** — select and connect to one of the found devices
- **Batch NTP update** — set an NTP server on all discovered or manually entered devices at once
- **Time sync status** — compare device time with PC time

### Item 9 — Auto-setup NTP server (experimental mode)

Fully automatic mode:
1. Asks for the ADB port and scans the local network, discovering Android TV devices
2. Connects to the selected device
3. Detects your region based on the system timezone
4. Tests all available NTP servers (110+) and measures response times
5. Shows the Top-5 fastest servers with RTT, success rate, and offset
6. Automatically recommends the best server
7. Installs the selected server on the device

### Item 10 — Terminal mode

Interactive mode for executing any ADB and system commands. Useful for advanced users:
- App management (install, uninstall)
- File transfers (push/pull)
- Screenshots and screen recording
- System diagnostics
- Device reboot

Commands: `help` — help, `clear` — clear screen, `exit` — exit terminal mode.

### Item 11 — Android 11+ wireless debugging

Use this item when Developer options on your device offer **"Wireless
debugging"** instead of "Network debugging" — that is how Google TV Streamer and
Chromecast with Google TV behave on Android 14.

It opens a submenu with two actions.

**1. Pair a device with a code.** A one-off procedure: afterwards the computer
and the TV know each other and you never have to repeat it.

1.  On the TV: **Developer options** > **Wireless debugging** > **Pair device
    with pairing code**. The screen shows an address, a port and a 6-digit code.
2.  In the program, choose item 1. It searches for devices over mDNS and shows a
    list — pick one by number. If mDNS is unavailable, type the address by hand
    as `192.168.1.20:41234` (the **pairing** port from the TV screen).
3.  Enter the 6-digit code.
4.  Once pairing succeeds the program asks for the connection address.
    **Note: this is a different port.** It is shown on the main "Wireless
    debugging" screen, not in the pairing dialog. Over mDNS the program fills it
    in for you.

**2. Find devices via mDNS.** Shows two groups: devices waiting to be paired
(the code dialog is open on their screen) and devices already paired and ready
to connect. From the second group you can connect straight away by number.

> **Why mDNS matters here.** Wireless debugging hands out **random** ports — a
> different one for pairing and for connecting — and they change every time it
> is switched on. mDNS discovery saves you from copying them off the screen. The
> program first tries the bundled `adb`, and falls back to the `zeroconf`
> library if that build has no mDNS backend. If neither works, entering the
> address by hand still does.

Once paired and connected, every other menu item works as usual: changing NTP,
device information, terminal.

### Item 0 — Exit

Closes the program.

## Android application

A separate APK that does what the desktop program does, but from a phone — or
straight from the TV it is installed on.

### Two modes

The application detects where it runs from the system UI mode and the leanback
feature:

*   **Phone or tablet** — controls the TV over the network, exactly like the
    desktop program. This is the main, working scenario.
*   **The Android TV itself** — tries to reach its own `adbd` at
    `127.0.0.1:5555`. This is a **hypothesis, not a guarantee**: not every
    firmware accepts an ADB connection from itself. That is precisely why
    "Connect to this device" is a separate button — a refusal has to be visible
    rather than look like a malfunction.

### What it can do

*   Connect to an address such as `192.168.1.20` or `192.168.1.20:37105`.
*   Pair with a 6-digit code (Android 11+ wireless debugging). The pairing port
    and the connection port are **different** — both are shown on the TV screen,
    and confusing them is the single most common cause of failure.
*   Discover devices on the local network over mDNS, showing which ones are
    waiting to be paired and which are ready to connect.
*   Pick an NTP server by country (search by code or name), from the list of
    alternative servers, or type an address by hand — a domain name or an IP.
    Validation matches the desktop version.
*   **Verification before applying.** The app sends a real SNTP request and
    parses the reply: the address must answer as a time server and report a
    time close to the real one (offset within 60 seconds). An open port is not
    enough — otherwise the TV would get an address that "works" while its clock
    stands still. An address that fails is not applied, but an "Apply anyway"
    button remains: UDP port 123 is blocked by many mobile carriers and some
    home routers, and a hard refusal would leave you with no options at all.
*   **Finding the fastest server.** Tests the whole reference list (122
    addresses) and shows the best five with round-trip time and reply rate. The
    test runs from the phone's network and can be stopped at any time.
*   Device details: model, manufacturer, Android and API version, serial number,
    CPU and core count, memory, screen and its density, time zone, locale,
    battery, kernel, uptime, current NTP server.

### Permissions

The application asks for access to nearby devices. This is not about location:

*   since **Android 13** the system `NsdManager` behind mDNS returns an
    **empty list without complaining** unless `NEARBY_WIFI_DEVICES` is granted —
    there is no way to tell that apart from "nothing on the network";
*   since **Android 17**, without `ACCESS_LOCAL_NETWORK`, connections to
    `192.168.x.x` are severed, and the local network is all this application
    ever talks to.

A refusal does not break anything: the address can always be typed by hand, and
the "Grant permission" button stays on screen.

### Not verified yet

An honest list — the application has **never been run on a real device**:

*   The loopback hypothesis: whether a TV firmware accepts a connection from
    itself.
*   Code pairing and mDNS discovery on a real Android TV.
*   Changing the NTP server, and whether it survives a reboot.
*   How the interface looks and whether the remote (D-pad) reaches everything.

That is why the release carrying the APK is marked as a pre-release. It says
nothing about the desktop half, which is tested and stable.

## Compatibility

The program has been tested and should work on Android TV devices (including Nvidia Shield) that meet the following requirements:

*   ADB over the network — in either flavour: the classic "Network debugging"
    (port 5555) or Android 11+ "Wireless debugging" with a pairing code.
*   Support for NTP server management via `adb shell` commands.

Verified on devices from Android 9 to 16. On Google TV Streamer and Chromecast
with Google TV updated to Android 14 only wireless debugging is available — use
menu item 11.

**Supported Operating Systems:**
*   Windows 10/11
*   Linux (Ubuntu, Debian, Fedora, etc.)
*   macOS

**Android application:** Android 6.0 and newer; code pairing additionally
requires Android 11 or newer on the controlled device.

## License

The source code and the desktop builds are distributed under the
**Apache License 2.0** — the full text is in [LICENSE](LICENSE). You are free to
use, modify and redistribute them, including commercially, provided you keep the
copyright notice and state any changes you made.

**The pre-built APK is distributed under the GPL-3.0** — the text is in
[LICENSE-GPL-3.0.txt](LICENSE-GPL-3.0.txt). The reason: it links `spake2-java`,
which is under that licence and without which code pairing on Android 11+ is
impossible. Apache-2.0 is compatible with the GPL-3.0 in this direction, so the
project's source stays Apache-2.0 while the assembled APK falls under the
GPL-3.0. The complete corresponding source is this repository at the release tag.

### Third-party code

The full list is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). In short:

*   **Android Debug Bridge (`adb`)** — shipped inside the release archives,
    Apache-2.0, part of the Android Open Source Project. Google's Android SDK
    Terms explicitly exempt open source components from their restrictions, so
    redistribution follows Apache-2.0.
*   **Python libraries** — Apache-2.0, BSD, MIT and PSF, all permissive.
*   **`spake2-java`** — **GPL-3.0**, arrives with `kadb-android` and performs
    the code pairing. APK only; it is not part of the desktop builds.
*   **`kadb-android`, AndroidX, Compose, Kotlin** — Apache-2.0,
    **BouncyCastle and elisabeth** — MIT. All of these are APK only.
*   **python-zeroconf** — **LGPL-2.1-or-later**, used as a fallback for mDNS
    discovery. It ends up inside the pre-built executables, so the LGPL requires
    that you be able to replace it with your own version: see
    THIRD-PARTY-NOTICES.md for rebuild instructions. The `zeroconf` import is
    optional — the program runs without it, using the mDNS support of the
    bundled `adb`.

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

**Changes:**

The author(s) reserve the right to make changes to this disclaimer at any time without prior notice. Your continued use of the program after any changes are made will signify your acceptance of the modified terms.
