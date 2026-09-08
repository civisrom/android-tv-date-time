[Русский](https://github.com/civisrom/android-tv-date-time/blob/main/README.md)

# Android TV Time Fixer

**Fixing Time Synchronization Issues on Android TV**

## Contents

- [Key features](#key-features) and [getting started](#getting-started)
- [Installation](#installation) and [Android TV setup](#android-tv-setup)
- [USB](#usb-debugging) and [Android 11+ wireless debugging](#modern-wireless-debugging-on-android-tv)
- [Main menu](#main-menu) and [desktop instructions](#how-to-use-the-program)
- [Android application](#android-application)
- [Compatibility and verified scenarios](#compatibility)
- [License](#license) and [disclaimer](#disclaimer)

## About the Program

**Android TV Time Fixer** changes the Network Time Protocol (NTP) server
address on Android TV and other compatible Android devices through ADB,
Android's debugging interface. The normal workflow does not require root,
but the firmware must allow the setting to be changed through ADB.

Two versions are available:

*   **Desktop program:** a console utility for Windows, Linux and macOS with
    batch operations, favorites and a terminal.
*   **Android app:** an APK for a phone, tablet or Android TV itself. Buttons
    handle the main actions without a computer or manual ADB commands.
    **The Android app operates in test mode.**

## Problem Description

Some TVs and set-top boxes lose the correct time after a power interruption.
If automatic synchronization cannot reach an NTP server, the clock remains
wrong: HTTPS certificate checks may fail, internet apps may stop working,
and the date has to be set manually.

One possible cause is that the device's network cannot reach the default
`time.android.com` server. The program lets you specify an alternative
server available in your region. It changes the setting; Android then
performs synchronization while automatic date and time is enabled.

**Connected, no internet access** does not by itself establish an NTP problem:
DNS, the router or Android's internet connectivity check may also be responsible.
Changing the server helps when obtaining time is the problem; it does not
replace troubleshooting other network faults.

## Key Features

*   **USB connections:** Windows, Linux, macOS and Android devices with USB
    host/OTG. Select a specific device and manage NTP without an IP address.
    See [USB debugging](#usb-debugging).

*   **Network debugging over Wi-Fi or Ethernet:** connect by IP address and
    ADB port (usually `5555`) to devices with classic network debugging enabled.
    Available in both the desktop program and the Android app.

*   **Modern wireless debugging on Android 11+:** support for the newer
    Wireless debugging mode on devices that offer it. Pair using a six-digit
    code, discover devices via mDNS and connect securely over TLS without a
    USB cable — from Windows, Linux, macOS or the Android app.
    See [Modern wireless debugging](#modern-wireless-debugging-on-android-tv).

### Shared functions

*   **NTP setup:** choose by country, search by code or name, or enter a domain
    name or IP address. The shared reference contains **77 countries and
    45 alternative servers**, including regional pools, Cloudflare, Google
    and other public NTP servers. Development checks keep the two reference lists consistent.
*   **Time-server checks:** real NTP requests, round-trip time (RTT), successful
    reply percentage and offset from the controlling device's clock.
    Check before applying a server or find a suitable one from the reference.
*   **Setting verification:** the program reads the NTP value back after writing
    it. This confirms the stored address; Android itself performs the actual
    clock synchronization.
*   **Device details:** model, manufacturer, Android/API, CPU, memory, display,
    serial number, timezone and uptime. Available fields depend on firmware;
    the desktop version also shows network parameters and compares device time
    with the computer's clock.
*   **Saved ADB trust:** reuse ADB keys and verify the connection with a command
    on the device. Revoking access may require authorization or pairing again.
*   **Russian and English interface:** the desktop program remembers the language
    selected at startup; the APK follows the system language.
*   **Visible version:** in the desktop main menu and below the Android app title;
    the desktop program also accepts `--version`.

### Desktop features

*   Console menus for Windows, Linux and macOS; release builds include ADB.
*   Local subnet scanning with a selectable ADB port, plus separate mDNS discovery.
*   Batch NTP updates for discovered or manually entered devices.
*   Experimental automatic setup: choose a device, detect the region, test
    servers, offer the best five and apply after user confirmation.
*   Favorite servers, copy/paste, a saved last address, and JSON settings
    export and import.
*   A terminal for ADB and system commands: apps, files, screenshots, diagnostics
    and reboot, subject to the permissions of the connection.
*   File logging and a separate ADB server port. Data locations and interaction
    with other ADB instances are explained below.

### Android app features

*   A separate APK for phones, tablets or Android TV: configuration without a computer.
*   Always-expanded connection IP and NTP settings; Android 11+ pairing,
    additional lists and help can be collapsed.
*   Manual time zone changes on the connected device with result verification,
    preserving the NTP server and automatic clock synchronization.
*   Automatic mDNS discovery with separate pairing and connection addresses;
    found devices have a green label alongside their name and address.
*   NTP checks before connecting, a best-five server search with progress and
    a Stop button, and a choice of domain name or resolved IP address.
*   USB host/OTG with device selection and discovery diagnostics.
*   A local operation history through Diagnostics, report copying and clearing.
    Logs are never sent automatically.
*   Android TV remote navigation, large fonts and landscape layouts.

## Getting started

1. Install the desktop program or the APK on a phone/tablet.
2. Enable debugging on the **controlled device** whose time needs fixing.
3. Choose a connection: **USB** uses a cable and RSA authorization;
   **classic network ADB** uses an IP and port; **Android 11+ wireless debugging**
   uses code pairing followed by a separate connection port.
4. Connect and read the current details. Use **item 5** on the desktop;
   the APK shows connection status and device details on the main screen.
5. Choose and check a time server, apply it and verify the value read back.
   Use **item 1 or 2** on the desktop, or **Time server** in the APK.

Network ADB requires the devices to reach each other over the local network.
The phone can use Wi-Fi while the TV uses Ethernet on the same router, provided
the firmware permits the chosen debugging mode. Guest networks, client isolation,
VPNs or multicast filtering may prevent connections or discovery. USB does not
need a shared network, but NTP checks and subsequent synchronization need network access.

## Installation

### Windows

1.  Download the `AndroidTVTimeFixer-windows.zip` archive from the [Releases](https://github.com/civisrom/android-tv-date-time/releases) section.
2.  Extract the archive to a convenient location on your computer, for example, `D:\AndroidTVTimeFixer`.
3.  Run `AndroidTVTimeFixer.exe` or use `start.bat` / `start.ps1`.

Run via PowerShell

1.  Open **PowerShell** normally. Everyday use does not require administrator privileges.
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

1. Download `AndroidTVTimeFixer-macos.zip` from [Releases](https://github.com/civisrom/android-tv-date-time/releases).
   The current prebuilt file targets **Apple Silicon (arm64)**.
2. Extract the archive and open Terminal in its folder. This is a console program:
   ```bash
   chmod +x AndroidTVTimeFixer
   ./AndroidTVTimeFixer
   ```
3. If macOS blocks the downloaded file, check its source and the stated reason
   under **System Settings → Privacy & Security**.
   Intel Macs need a separate x86-64 build.

### Android (APK)

1.  Download `AndroidTVTimeFixer-2.6.2.apk` from the [2.6.2 prerelease](https://github.com/civisrom/android-tv-date-time/releases/tag/v2.6.2). [What's new](release-notes/v2.6.2-en.md).
2.  Verify it against the `.apk.sha256` file next to it:
    ```bash
    sha256sum -c AndroidTVTimeFixer-2.6.2.apk.sha256
    ```
3.  Install it:
    *   **On a phone** — open the file and allow installation from unknown
        sources for your file manager or browser.
    *   **On the Android TV itself** — either `adb install AndroidTVTimeFixer-2.6.2.apk`
        from a computer, or any file manager on the TV. The icon appears both in
        the regular launcher and in the Android TV launcher.

Requires **Android 6.0** or newer. See
[Android application](#android-application) for details.

**Google Play Protect.** This APK is distributed through GitHub Releases.
Installing outside Google Play may trigger a scan prompt or warning; not every
user will see one. Read the stated reason: an unknown developer and a detected
threat are different findings. The checksum helps check download integrity,
while a compatible signature is required to update an installed app.
See [Google Play Protect help](https://support.google.com/googleplay/answer/2812853).

### Application data

The **desktop program** is portable: by default its settings and log live **next to the executable**
(`keys/`, `adb/`, `settings.json`, `saved_servers.json`, `android_tv_fixer.log`).

- `keys/` — the key for direct "network debugging" connections (legacy ADB protocol).
- `adb/` — a private `adb` home for Android 11+ wireless debugging: it holds the key
  and the list of paired devices. On the first run, existing keys from `~/.android` are
  copied here so that already paired devices keep working. **Linux and macOS only:** on
  Windows `adb` resolves the user profile through a system API and ignores environment
  variables, so its keys stay in `%USERPROFILE%\.android` there (verified against
  platform-tools 37.0.1).

The program uses its own ADB server port on **every** platform and does not
terminate other sessions automatically. Different ADB servers can still compete
for the same USB device; a separate port does not remove that conflict.

When the program folder is not writable (for example, under `Program Files`), the data falls back to the user data directory:

- Windows: `%LOCALAPPDATA%\AndroidTVTimeFixer`
- Linux: `$XDG_DATA_HOME/AndroidTVTimeFixer` or `~/.local/share/AndroidTVTimeFixer`
- macOS: `~/Library/Application Support/AndroidTVTimeFixer`

In that case, existing settings and ADB keys from the application folder are copied
automatically on first launch.

The APK keeps its data separately in private Android app storage. Its log
retention rules are described under [Diagnostics](#if-the-app-misbehaves).

## Android TV Setup

### Step 1. Enable developer mode

1.  On your Android TV, open: **Settings** > **Device Preferences** > **About**.
2.  Click on the **"Build"** item 7 times to unlock developer mode.
3.  Go to: **Device Preferences** > **Developer options**.

### Step 2. Enable debugging — USB or one of the network options

For a cable connection, enable **USB debugging** and follow
[USB debugging](#usb-debugging). The options below apply to network connections.

Look for **one** of these entries under Developer options. Which one you get
depends on both the Android version and the firmware.

**Option A — classic network debugging.** Firmware may label its switch
**Network debugging**, **ADB debugging**, or even **USB debugging**. If the
device exposes legacy ADB on port **5555**, the program only needs its IP
address regardless of the label used in Settings.

> **Xiaomi Mi TV and Mi Box.** Some Xiaomi models and firmware versions have no
> separate Network debugging entry. Xiaomi itself documents a single **ADB
> debugging** control under Account & Security for these devices. On some
> models, enabling that control (labelled USB debugging in certain translations)
> also exposes ADB over the local network on port **5555**. Enter the TV's IP
> address manually and the program will supply port 5555. This is not guaranteed
> for every Xiaomi TV and can change with firmware; if the port remains closed,
> use a separate network/wireless debugging option or configure ADB over USB by
> the standard method. See [Xiaomi's official instructions](https://www.mi.com/sg/support/article/KA-06513/)
> and [a model-specific owner report for Mi TV 4A/4S](https://4pda.to/forum/index.php?showtopic=957045&st=6200).

**Option B — paired Wireless debugging (Android 11+).**
If the firmware offers this mode, enable it and allow it on your network.
Use **item 11** in the desktop program or **Pair a device** in the APK.
Keep the target's code dialog open until pairing finishes. The subsequent
connection uses a separate address from the main debugging screen or mDNS.

The program detects the protocol while connecting, but the address must
contain the correct port: an IP with the automatically supplied `5555`
is usually insufficient for the modern mode.

### Step 3. Allow automatic time synchronisation

1.  Open: **Settings** > **Date & Time**.
2.  Enable: **Auto date & time** > **Use network time**.

Without this the device will not contact the NTP server you set.

### Step 4. After the setup

After setup, turn off the debugging mode you used if it is no longer needed.
The address is stored in system settings and normally survives a reboot;
a reset or firmware-specific behavior can change it. Disabling debugging
does not disable automatic time synchronization.

## USB debugging

Use a **data cable**, enable **USB debugging** on the target Android device,
and accept its RSA authorization prompt. The target TV/box must expose ADB
through a supported device/OTG port. A host-only port intended for USB drives
cannot provide this connection; check the manufacturer’s port documentation.
An Android phone controlling the target must support USB host/OTG.
[Android USB host model](https://developer.android.com/develop/connectivity/usb/host).

**Windows, Linux and macOS:**

1. Connect the target to the computer and enable USB debugging on the target.
2. Choose **12. Connect over USB**, enter the device number and press Enter. You can also enter
   `u` in the address prompts for menu items 1, 2 and 5.
3. If authorization is needed, accept the RSA prompt on the target, press `r`
   to refresh the list, and select it again.
4. Once the connection is verified, use the usual NTP and device-information
   menu items. Press Enter at the next address prompt to reuse the selected USB device.

After selecting a USB device, **9. Auto-setup NTP** also uses that device,
without searching the network for another target.

Windows may require an [OEM ADB driver](https://developer.android.com/studio/run/oem-usb).
On Ubuntu/Debian, install the `android-sdk-platform-tools-common` udev rules
and check `plugdev` membership if USB access is denied; log in again after
changing groups. macOS normally needs no separate ADB driver.
[Official workstation setup](https://developer.android.com/studio/run/device).

Releases bundle ADB 37.0.1. When running from source, install Platform Tools
and put `adb` on `PATH`, or put the binary in the project’s `resources/`
directory (Windows also needs `AdbWinApi.dll` and `AdbWinUsbApi.dll`). The
application uses its own ADB server. Another server may already own the USB
interface: close that session and reconnect the cable. Other processes are
not terminated automatically. Terminal mode accepts regular commands such as
`adb devices -l`, `adb -d shell ...` and `adb -s SERIAL shell ...`.

**Android application:**

1. Connect the controlling phone/tablet to the target using OTG and a data cable.
2. In **USB debugging**, refresh the list and choose **Connect via USB**.
3. Allow USB access in the phone’s system dialog, then accept the separate
   RSA prompt on the target. These are two different permissions.
4. After the connection probe succeeds, NTP settings and device information
   are available. Unplugging closes the session; select the device again to
   reconnect. Root and a six-digit pairing code are not required for USB.

An empty list distinguishes Android seeing no USB devices from USB being present
without an ADB interface. Press **Refresh USB devices**; Diagnostics also records
the result. Android may offer to open the app when an ADB device is attached;
connecting still requires pressing the button.
An empty list alone does not establish the cause. **Diagnostics → Details** for
a USB search includes device counts and system USB role flags. Role information
is optional and may be unavailable or delayed by firmware; a missing flag appears
as `unknown`, not as OTG being disabled.

For **SHIELD without micro-USB**, use port 1, furthest from HDMI, in PC connection
mode. The phone must be the USB host. A regular USB-A to USB-C cable may select
the opposite role; use an appropriate OTG data connection. See [NVIDIA instructions](https://nvidia.custhelp.com/app/answers/detail/a_id/4344/kw/10)
and [Chromium USB-C cable guidance](https://www.chromium.org/chromium-os/developer-library/reference/hardware/cable-and-adapter-tips-and-tricks/).

USB ADB does not require a shared Wi-Fi network. NTP probing still uses the
controller’s Internet connection, and the TV needs access to the selected NTP
server for subsequent synchronization. The application does not enable
debugging automatically or switch the target to `adb tcpip`. Network scanning
and batch network operations continue to use IP addresses.

## Modern wireless debugging on Android TV

Modern Wireless debugging was introduced for phones in Android 11, with official
TV support starting at Android 13. The name Android 11+ refers to this paired
debugging mode; it does not mean that every Android 11 TV necessarily offers it.

### When to use this mode

On some Google TV Streamer and Chromecast with Google TV firmware after the
Android 14 update, code-based Wireless debugging is the main network connection
method. Follow the options offered by your device: the Android version alone
does not guarantee identical menus across manufacturers. Instructions for
classic port `5555` do not replace pairing with this service.

### How the modern mode differs

| | Classic Network debugging | Modern Wireless debugging |
|---|---|---|
| First access | RSA confirmation or an already authorized client | mandatory pairing with a 6-digit code |
| Port | usually fixed at `5555` | separate, dynamic pairing and connection ports |
| Discovery | IP and port are commonly entered by hand | services are advertised on the LAN through mDNS |
| Transport | plain ADB TCP | authenticated TLS connection after pairing |
| Reuse | a saved address often works again | trust persists, but the current port can change |

This creates three important rules:

*   **Pairing is required.** A new client is refused until you enter the
    six-digit code from the currently open dialog. The key remains trusted
    until authorization is revoked, app data is cleared, or the app is reinstalled.
*   **The ports are random.** Pairing and connecting use **different** ports,
    and they can change when debugging or the code dialog restarts. Use the
    current values shown on screen or discovered through mDNS.
*   **The connection is encrypted.** Older ADB clients simply do not speak it.

mDNS therefore solves a real usability problem: the program obtains the current
pairing and connection endpoints from the device's own advertisements instead
of making you copy changing ports from the TV. Both addresses can still be
entered manually when a router blocks multicast.

See the [official Android Debug Bridge documentation](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)
for platform requirements and the standard pairing procedure.

### Using this mode

Windows, Linux and macOS release builds include the required ADB;
Android Studio and a separate SDK installation are unnecessary. Open
[item 11 — Android 11+ wireless debugging](#item-11--android-11-wireless-debugging).
After pairing, the usual menu items use the encrypted connection.
In the APK, use the always-expanded
[Pair a device form](#6-pair-a-device--the-code-for-android-11-and-newer).

Both programs distinguish pairing and connection services in mDNS and verify
the connection with a command on the device. A listed service does not yet
mean the device has authorized this client. When mDNS is unavailable, enter
the addresses from the current debugging screens manually.

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
12. Connect over USB
 0. Exit
```

## Screenshots

### Desktop program

![Main menu](screenshots/en.png)

## How to Use the Program

### Choosing a device and checking NTP

Menu items using the shared address prompt (such as 1, 2 and 5) accept the
inputs below. With USB already selected, the program first offers to reuse
that device; `n` lets you choose another.

| Input | What happens |
|---|---|
| a number from the list | connect to that discovered device |
| Enter | reuse the selected USB connection or saved address, as indicated by the prompt |
| `192.168.1.20` or `192.168.1.20:37105` | enter the address by hand |
| `m` | search the network again (the port may have changed) |
| `s` | scan the whole subnet |
| `192.168.1.0/24` | scan the given subnet |
| `u` | choose a USB device |
| `q` | back to the menu |

For mDNS, the program uses the bundled `adb` with a `zeroconf` fallback.
Unlike subnet scanning, this discovers advertised current ports; a subnet
scan checks one selected port.

Before writing NTP, the program checks the reply and its offset from the
computer's clock (at most 60 seconds). Check the computer's time first.
A successful write is confirmed by reading the setting back; it does not
yet confirm that the TV has synchronized its clock.

### Item 1 — Change NTP time server by country code

Choose a device through the shared address prompt or reuse the selected USB
device. After connecting, the program shows current settings and asks for a
two-letter country code (such as `ru`, `by`, `de`). Enter `?name` to search by
country name. The regional server is checked, written to the device and
read back for confirmation.

> **Tip:** If you don't know your country code, first open **item 3** — it displays a full list of country codes with names and NTP servers. Copy the desired code to the clipboard and paste it when prompted in item 1.

### Item 2 — Change NTP time server to custom

Similar to item 1, but enter an NTP server domain name or IP address instead
of a country code. The program checks the format and NTP response, then sets
the server and reads back the result.

> **Tip:** You can get the NTP server address from **item 3** (servers by country) or **item 4** (alternative servers — Cloudflare, Google, etc.). Open the desired item, copy the server address to the clipboard, and paste it when prompted in item 2.

### Item 3 — Show country codes with country names and NTP servers

Displays a complete list of supported country codes (77), their names, and corresponding NTP servers. Interactive search is available. Results can be copied to the clipboard.

> **Using the results:** The copied country code can be pasted into **item 1**, and the copied NTP server address can be pasted into **item 2** for manual setup.

### Item 4 — Show available alternative NTP servers

Shows a list of alternative NTP servers: regional pools, Cloudflare, Google, and others. Results can be copied to the clipboard.

> **Using the results:** The copied server address can be pasted into **item 2** for manual installation on the device.

### Item 5 — Show current device information

Connects to the device and displays detailed information: model, manufacturer, Android version, serial number, CPU, RAM, screen resolution, network parameters (IP, MAC), current NTP server, timezone, uptime, and a comparison of device time vs PC time.

### Item 6 — Ping NTP servers

Checks the 122 reference addresses using real NTP requests rather than ICMP ping.
Shows round-trip time (RTT) and successful reply percentage, sorting by
availability, reply percentage and speed. Many unreachable addresses can make
the check take longer because each request must time out.

### Item 7 — Server management

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

Opens a submenu for managing favorite servers:

- **Show favorites** — list of saved servers

- **Add current server** — saves the current NTP server from the device to favorites

- **Copy server** — copies the current device server to clipboard

- **Paste server** — sets the server from clipboard on the device

- **Remove server** — removes a selected server from the favorites list

- **Export / Import settings** — save and restore all settings (language, IP, favorite servers) to a JSON file

#### Export / Import Settings Submenu

```
1. Export settings to file
2. Import settings from file
3. Back
```

### Item 8 — Network scan & batch NTP update

```
1. Scan local network for Android TV devices
2. Connect to discovered device
3. Batch NTP update (all discovered or entered IPs)
4. Show device time sync status
5. Back to main menu
```

Opens a submenu for working with multiple devices:

- **Scan network** — first asks for the ADB port (Enter for 5555; the value is remembered), then discovers Android TV devices on the local network. Results are shown as `IP:port`, so a non-standard port is carried over automatically

- **Connect to discovered device** — select and connect to one of the found devices

- **Batch NTP update** — set an NTP server on all discovered or manually entered devices at once

- **Time sync status** — compare device time with PC time

### Item 9 — Auto-setup NTP server (experimental mode)

Helps choose a device and a suitable server:

1. Reuses the selected USB device. If USB is not selected, asks for the ADB
   port and scans the local network.
2. Connects to the only device found, or offers a choice when several are found.
3. Detects the region from the computer's timezone and checks the NTP reference.
4. Keeps responding servers with a clock offset of at most 60 seconds.
   Successful reply percentage comes first, then RTT; regional servers receive
   a small preference when the successful reply percentage is equal.
5. Shows up to five best candidates. You can select another numbered result.
6. Asks for installation confirmation, writes the address and verifies it.

For wireless debugging with different dynamic ports, it is more convenient to
discover the device through mDNS in item 11 and set NTP in item 1 or 2:
subnet scanning tests one specified port.

### Item 10 — Terminal mode

Interactive mode for executing any ADB and system commands. Useful for advanced users:
- App management (install, uninstall)
- File transfers (push/pull)
- Screenshots and screen recording
- System diagnostics
- Device reboot

Commands: `help` — help, `clear` — clear screen, `exit` — exit terminal mode.

### Item 11 — Android 11+ wireless debugging

```
1. Pair a device with a code
2. Find devices via mDNS
3. Return to main menu
```

Use this item for **Wireless debugging** with code pairing. Classic ADB on
port 5555 does not need this procedure.

It opens a submenu with two actions.

**1. Pair a device with a code.** Usually needed once for a saved ADB key.
Deleting the key, revoking access or authorization expiry can require pairing again.

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
(the code dialog is open on their screen) and devices ready to connect. From
the second group you can start connecting by number; a new TLS client may
still need pairing. That second group
also includes devices using the classic "Network debugging" — some firmwares
(an Nvidia Shield, for instance) have no "Wireless debugging" screen at all and
announce themselves only the old way.

Once paired and connected, every other menu item works as usual: changing NTP,
device information, terminal.

### Item 12 — Connect over USB

Opens the USB ADB device list. **Enter the desired device number and press
Enter**; `r` refreshes the list and `q` returns to the menu. For `UNAUTHORIZED`,
approve the RSA prompt on the target and refresh the list. After a probe
command succeeds, the program names the connected device and suggests item 5
for details or items 1 and 2 for NTP. Cable, driver and permission requirements
are in [USB debugging](#usb-debugging).

### Item 0 — Exit

Closes the program.

## Android application

The APK configures one connected device through USB, classic network ADB or
paired wireless debugging. The main workflow is to connect, choose and check
NTP, apply the setting and read back the result. Pairing is only needed for
the modern wireless mode, not for every connection method.

### Before you start

1. For network ADB, ensure the devices can reach each other on the local network.
   Avoid isolated guest networks. For USB, prepare a phone/tablet with host/OTG,
   a data cable and an appropriate port on the target.
2. Enable debugging **on the target device**, following
   [Android TV Setup](#android-tv-setup). The controlling phone does not need
   USB debugging enabled: it needs host/OTG for a cable connection.
3. Check the date and time on the device running the APK: NTP replies are
   compared with its clock. If the APK runs on the TV whose clock is wrong,
   first set approximately correct date and time on that TV.
4. If network connections fail, check VPNs, router client isolation and
   automatic switching to mobile data. Disabling cellular service is not
   required for every connection.

### Application screen sections

The app is a single scrolling screen. The connection IP address, main time
server settings and pairing form always remain expanded, including after
connection. Additional lists and help sections can be collapsed.

#### 1. Title and mode

One line under the name: "Running on a phone: it will connect to a TV over the
network or USB" or "Running on a TV". The app works this out by itself; nothing to set.

#### 2. "Connect to a device"

Shows the current state, **colour-coded** so it can be read at a glance:

*   **green** — "Connected to 192.168.0.112:5555", the link is up;
*   **red** — "Not connected", or the reason it failed;
*   plain — "Connecting to…", while an attempt is in progress.

**The input field** takes two forms:

| What to enter | When |
|---|---|
| `192.168.0.112` | usually; port `5555` is added for you |
| `192.168.0.112:37105` | when debugging uses a non-standard port |

Find the TV's address in its settings: **Settings → Network & Internet →** your
network, or **Settings → About → Status**.

The **Connect** button verifies the link for real: it runs a probe command on
the TV and checks the reply. So "Connected" here means there is a working
connection, not that the address merely looked valid.

**What the button can answer:**

*   *"Confirm the debugging prompt on the device screen"* — the TV is showing
    "Allow debugging from this computer?". Press **Allow** there (ticking
    "Always allow" helps), then connect again.
*   *"The device needs pairing with a code"* — this is Android 11+ wireless
    debugging; see Pairing below.
*   *"Cannot reach the device"* — no answer at that address: wrong network, a
    typo, or debugging is off.
*   *"Invalid address"* — what you typed is not an IP address.

Once connected, a **Disconnect** button appears. The IP field and pairing
form remain visible; editing an address alone does not change the current connection.

#### 3. "Devices found automatically on the network"

The app looks for TVs over mDNS — the protocol devices use to announce
themselves on a local network. **You need neither the address nor the port**: a
TV that is found shows up here on its own.

The section can be collapsed and opens automatically when new results arrive.
A green **Device found** label means discovery, not an established connection.
Each row shows the name, address and service type:

*   **"Network debugging"** — the classic debugging on port 5555. The
    **Connect** button starts a connection; RSA authorization may be required.
*   **"Ready to connect"** — a wireless ADB TLS endpoint is advertised.
    This does not prove that this app is paired; a new client may still need a code.
*   **"Waiting to be paired"** — the pairing dialog is open on the TV. You
    cannot connect until a code is entered, so the button here is **Pair**: it
    puts the address into the pairing form below.

An empty list is not a problem: type the address by hand in the section above.

**Authorization.** For classic ADB, approve the target's RSA prompt showing
the key fingerprint; **Always allow** saves trust in the client. Modern
wireless debugging establishes trust through code pairing instead. These
are different procedures; appearing in mDNS does not replace either one.

**Permissions.** The system mDNS APIs used here do not require
`NEARBY_WIFI_DEVICES` on Android 13-16. With the current `targetSdk = 36`,
Android 17 does not need an extra prompt either. A future target SDK 37
build must request `ACCESS_LOCAL_NETWORK`; denial affects direct connections
as well as discovery. See [Android's local-network permission rules](https://developer.android.com/privacy-and-security/local-network-permission).

#### 4. "Time server"

This section is always visible below the network discovery menu. You can choose and
check a server address before connecting to a device. **Apply** becomes available
once connected; an explanation is shown until then. Search, lists and scanning
are inside **Choose a server**. An ongoing scan and its Stop button
remain visible when the picker is collapsed.

After connecting, **Current:** shows the value read from the device.
A stored address is green. **No time server is set** means the app did not
obtain a custom `ntp_server` value; Android normally uses the firmware default.
If reading the setting is unsupported or fails, an empty field does not
identify which system server is being used.

The outcome is marked the same way: **green** for "Time server set to …",
**red** for any failure. The Check button follows suit — a usable server is
green, a rejected one red.

**Choosing a server:**

*   **Search.** Start typing a country code, a country name, or part of an
    address: `ru`, `by`, `kz`, `Russia`, `cloudflare`. Tapping a result **puts
    the address into the input field** — nothing is changed yet.
*   **The country list.** With the search empty there is a **Show countries and
    their codes (77)** button. You do not have to remember the codes: each row
    shows the code, the name and the address — `RU · Russia · ru.pool.ntp.org`,
    `KZ · Kazakhstan · kz.pool.ntp.org`, `BY · Belarus · by.pool.ntp.org`.
*   **The alternative-server list.** **Show alternative time servers (45)** —
    the same set as the desktop version: regional pools, Cloudflare, Google
    and other public NTP servers.
*   **By hand.** The "Time server address" field takes a domain name
    (`time.google.com`) or an IP address (`216.239.35.0`) — there is a reminder
    of that under the field.

The **Check** button sends a real NTP request from the device running the
APK and parses the reply. It checks the address, reachability, and NTP response.
The offset from the device's clock is informational and does not block applying a
server: the TV, box, or phone clock may be wrong. Possible outcomes:

*   *Answers as a time server: 42 ms, 100% of replies, clock offset +0.3 s* —
    the server passed the check from the current network.
*   *Does not answer as a time server: …* — no suitable reply was received;
    possible causes include server availability, blocked UDP/123 or DNS failure.

The phone and TV networks may impose different restrictions, so a successful
phone-side check does not guarantee that the TV can reach the server.

The **Apply** button does the same and, if the check passes, writes the address
to the TV. The result appears **right under the button**, and the "Current:"
line is updated with the value **read back from the device**.

*   *"Time server set to …"* — the address was saved and read back from the device.
*   *"The device still reports …"* — the command went through but the write did
    not happen. Usually this means the connection lacks permission to change
    secure settings.
*   *"Not applied: …"* — the address failed the check. The app explains why:
    an invalid address, DNS failure, no response, an invalid NTP reply, or a
    network error. The TV setting stays unchanged; correct the address,
    check the network, or choose another server and try again.

After a successful write, **device time verification** runs automatically.
It reads the device clock through ADB and compares it with a fresh reply from
the configured NTP server. The result appears nearby: agreement within
**5 seconds**, a remaining difference, insufficient measurement precision,
or unavailable data. ADB/NTP delays and whole-second clock readings are taken
into account.

The card shows the sampled device time in UTC and, when its time zone is
recognized, local time with the zone name, its difference from NTP, and
automatic date and time status. For example, 13:00 UTC and 16:00 in UTC+3
are the same moment, not a clock discrepancy. **Verify device time** repeats only the read
and comparison; it does not change settings. Android may update its clock later,
so a difference immediately after the write does not establish a failed setup.
A failed check does not undo confirmation that the server address was saved.
The NTP reply is received by the device running the APK: matching clocks do
not prove that the TV synchronized with this particular server.

The **Find the best one** button collapses the open country and alternative
lists, checks the 122-address reference, and shows
up to five suitable servers, ordered first by successful reply percentage and
then by response time. Duration depends on the network and timeouts. Progress
shows **Checked N of 122, M usable**; **Stop** keeps results already found.

Each result shows a name and the IP address obtained during the check.
Tapping either fills the input field and clears the results list.
The address field highlights three times; applying remains a separate action.
An IP can help with TV-side DNS problems, but a service is not guaranteed
to keep that resolved address permanently.

#### 5. "Time zone"

This section is visible on phones and TVs, starts collapsed, and follows the NTP
settings. Connect to the device, expand the section, and search by city or enter
an exact zone ID, such as `Europe/London` or `UTC`. Selecting a result fills the
field; only **Apply time zone** changes the setting.

Applying a zone enables manual zone selection so automatic detection does not
replace it. The NTP server and automatic date and time settings are preserved:
the zone controls local time display, while NTP synchronizes the clock itself.
The app reads the setting back to verify it. On failure, it attempts to restore
the previous settings and reports whether restoration was confirmed.

Required commands are checked on the connected device. AOSP includes them from
Android 9, but firmware restrictions can vary. If unsupported, use the TV’s date
and time settings. The phone’s Android version alone does not hide this menu.

#### 6. "Pair a device" — the code, for Android 11 and newer

The pairing section starts collapsed; tap its heading to expand it.
**Pair** on a discovered device expands the form, fills its address, and focuses the code field.
The pairing form and connection IP address remain available after connecting;
editing the fields does not disconnect the current device.

Pairing is only required where developer settings offer **Wireless debugging**.
If, as on an Nvidia Shield, you only have "Network debugging", pairing is not
needed at all — connect directly.

TLS pairing requires **Android 10+ on the device running the APK**, independently
of the TV version. Android 6-9 can use classic network ADB and USB with host/OTG support. The controlled device
must offer wireless debugging: officially Android 11+ for phones and Android
13+ for TVs, subject to firmware support. See the [official ADB documentation](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

**Step by step:**

1. On the main **Wireless debugging** screen, note the connection address,
   for example `192.168.0.112:37105`, or discover it through mDNS.
2. Open **Pair device with pairing code**. The TV shows a **six-digit code**
   and a line like `192.168.0.112:41234`.
   **That is the pairing address.**
3. Keep the code dialog open until pairing completes. Closing it may stop the
   pairing server; do not go back just to read the other port.
4. Fill in three fields in the app:

| Field | What to enter | Example |
|---|---|---|
| Pairing address | the address from the code dialog | `192.168.0.112:41234` |
| 6-digit code | the code on the TV screen | `473829` |
| Connection address | the address on the main debugging screen | `192.168.0.112:37105` |

5. Press **Pair and connect**.

**Keep the ports separate:** the address in the code dialog is for pairing;
the address on the main debugging screen is for connecting. Sharing an IP
address does not mean the two fields can use the same port.

**Use the code from the currently open dialog.** If the app answers "The device
rejected the pairing code", close the dialog on the TV, open it again and take
a fresh code, then check the pairing port again.

The APK now persists its ADB key in private, non-backed-up storage. Restarting
the app no longer changes the key. Upgrading from the old in-memory identity
requires pairing once more. Clearing app data, reinstalling, revoked access or
expired TV authorization can also require a new code. The pairing code is not
saved to disk. Deadlines are 10 seconds for TCP, 15 seconds for TLS/read
inactivity and 60 seconds for the overall pairing operation. App addresses
currently support IPv4 only.

Change history: [2.6.1](release-notes/v2.6.1-en.md) and
[2.6.2](release-notes/v2.6.2-en.md).

#### 7. USB debugging

Expand this section manually, or it opens automatically when USB ADB is
detected. It contains list refresh, device selection and the connection
button. If nothing is found, expand the USB troubleshooting help and open
the search details in Diagnostics. The connection steps and two permissions
are explained in [USB debugging](#usb-debugging).

#### 8. "Device"

Details read from the TV: model, manufacturer, Android and API version, serial
number, CPU and core count, memory, screen resolution and density, time zone,
locale, battery, kernel version, uptime and the current time server. The
**Refresh** button reads it all again.

Model, Android version, time server and time zone are visible immediately. The
time server label and value are green. Expand **All device details** for the rest.
Empty rows are hidden: if the firmware does not answer one command, only that
row disappears.

### If the app misbehaves

**Diagnostics** opens a local history of operations and errors. Returning preserves
entered addresses and expanded sections. **Details** beside an error opens its
matching record. After an unexpected closure, the next launch offers a link to
the saved crash details.

The private, non-backed-up store keeps up to **200 events for 7 days**, with a
combined **256 KiB** disk budget. It excludes pairing codes, keys, serial numbers,
entered addresses and ADB output. Technical details contain exception types and
bounded stack frames without exception messages. Nothing is sent automatically.
**Copy report** explicitly copies the history to the clipboard; **Clear** requires
confirmation. A storage failure is shown in Diagnostics without stopping device
operations.

### What the app does not do

Unlike the desktop version there is no subnet scanning, no batch update across
several TVs, no terminal mode, no favourite servers and no settings export. Use
the desktop program for those.

### TV Mode

When installed on Android TV itself, the APK offers `127.0.0.1:5555` for
legacy debugging. Whether this connection is accepted depends on the firmware.
For wireless debugging, use pairing and the current TLS connection port.


## Compatibility

The controlled Android device must expose ADB and allow `ntp_server` to be
read and changed through `adb shell`. An Android version number alone does
not guarantee this: firmware, ports and permissions also matter.

| Where the program runs | Requirements |
|---|---|
| Windows | Windows 10/11; the current prebuilt executable is x86-64 |
| Linux | The current x86-64 build is produced on Ubuntu 22.04 and needs compatible system libraries. Other architectures require a separate build |
| macOS | The current prebuilt executable is arm64 for Apple Silicon; Intel Macs need a separate build |
| Android: classic ADB or USB | Android 6.0+; the controller needs hardware host/OTG support for USB |
| Android: TLS pairing | Android 10+ on the APK device; the target must offer Wireless debugging |

Modern wireless debugging is officially supported from Android 11 on phones
and Android 13 on TVs, subject to firmware availability. For USB, the TV/box
needs a port supporting USB device mode, not only a port for storage devices.

**Verified scenarios and limitations:**

*   A user confirmed six-digit code pairing on a real device; the Android app
    section gives the procedure.
*   A Windows → NVIDIA SHIELD USB connection and reading device details have
    been confirmed in user testing.
*   Phone → SHIELD discovery is not yet confirmed: Android returns an empty
    USB list in the tested configuration. Better diagnostics do not establish
    that this hardware scenario has been fixed.
*   Automated UI checks run on Android 6 and Android 16. They exercise the app
    but do not replace physical testing of cables, USB roles or each TV model.

When reporting a problem, include both device models, Android versions,
connection method and the error text. In the APK, details are available through Diagnostics.

## License

The source code and the desktop builds are distributed under the
**Apache License 2.0** — the full text is in [LICENSE](LICENSE). You are free to
use, modify and redistribute them, including commercially, provided you keep the
copyright notice and state any changes you made.

**The pre-built APK is distributed under the GPL-3.0** — the text is in
[LICENSE-GPL-3.0.txt](LICENSE-GPL-3.0.txt). The reason: it links `spake2-java`,
which uses that license and implements code pairing in this APK. Apache-2.0
is compatible with the GPL-3.0 in this direction, so the
project's source stays Apache-2.0 while the assembled APK falls under the
GPL-3.0. The complete corresponding source is this repository at the release tag.

### Third-party code

The full list is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). In short:

*   **Android Debug Bridge (`adb`)** — shipped inside the release archives,
    Apache-2.0, part of the Android Open Source Project. Google's Android SDK
    Terms explicitly exempt open source components from their restrictions, so
    redistribution follows Apache-2.0.
*   **Most Python libraries** — Apache-2.0, BSD, MIT and PSF.
    The different `python-zeroconf` license is described below.
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
