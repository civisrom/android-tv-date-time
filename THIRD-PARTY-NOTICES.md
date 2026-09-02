# Third-party notices

Android TV Time Fixer is licensed under the Apache License 2.0 (see `LICENSE`).
It redistributes and depends on the third-party components listed below. Each
remains under its own license.

## Bundled binaries

### Android Debug Bridge (adb)

The release archives contain the `adb` executable from Android SDK
Platform-Tools r37.0.1, downloaded from Google's official distribution and
verified by SHA-256 during the build (see `.github/workflows/build.yml`).

`adb` is part of the Android Open Source Project and is licensed under the
**Apache License 2.0** — the same licence as this project, a copy of which is in
`LICENSE`. Google's Android SDK Terms and Conditions state that "use,
reproduction and distribution of components of the SDK licensed under an open
source software license are governed solely by the terms of that open source
software license", so redistribution here follows Apache-2.0.

Source: https://android.googlesource.com/platform/packages/modules/adb/

## Python dependencies

| Component | Licence |
|---|---|
| adb-shell | Apache-2.0 |
| cryptography | Apache-2.0 OR BSD-3-Clause |
| rsa | Apache-2.0 |
| packaging | Apache-2.0 OR BSD-2-Clause |
| click | BSD-3-Clause |
| colorama | BSD-3-Clause |
| psutil | BSD-3-Clause |
| pyasn1 | BSD-2-Clause |
| pyperclip | BSD-3-Clause |
| ifaddr | MIT |
| ntplib | MIT |
| platformdirs | MIT |
| rich | MIT |
| typing-extensions | PSF-2.0 |
| pyobjc-framework-Cocoa (macOS only) | MIT |
| **python-zeroconf** | **LGPL-2.1-or-later** |

## python-zeroconf and the LGPL

`python-zeroconf` (https://github.com/python-zeroconf/python-zeroconf) is
licensed under the **GNU Lesser General Public License, version 2.1 or later**.
It is used as an optional fallback for mDNS discovery when the bundled `adb`
has no mDNS backend of its own.

The pre-built release executables are produced with PyInstaller and therefore
contain a copy of this library. Section 6 of the LGPL requires that you be able
to replace it with your own modified version. You can do so as follows.

### Replacing python-zeroconf in a release build

1. Clone this repository at the tag matching your executable.
2. Install the dependencies: `poetry install --with dev`.
3. Install your modified `python-zeroconf` into that environment, for example
   `poetry run pip install --force-reinstall /path/to/your/zeroconf`.
4. Rebuild: `poetry run pyinstaller pyinstaller.spec`.

The build is fully described by `pyinstaller.spec` and reproduced automatically
by `.github/workflows/build.yml`, so the resulting executable is equivalent to
the published one apart from your replacement.

Alternatively, run the program from source instead of the executable. The import
of `zeroconf` is optional (see `adb_env` and `_mdns_via_zeroconf` in
`src/android_time_fixer.py`): the program starts and works without the library,
falling back to mDNS through the bundled `adb`, and picks up whatever version of
`python-zeroconf` you have installed.

A copy of the LGPL 2.1 licence text is available at
https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt and is distributed with
the `python-zeroconf` package itself.
