# Third-party notices

Android TV Time Fixer is licensed under the Apache License 2.0 (see `LICENSE`).
It redistributes and depends on the third-party components listed below. Each
remains under its own license.

## Bundled binaries

These apply to the desktop release archives
(`AndroidTVTimeFixer-{windows,linux,macos}.zip`).

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

## Android application (APK)

The `AndroidTVTimeFixer-*.apk` published with each release is a **separate
work** from the desktop executables and is subject to a different licence — see
"The APK and the GPL" below. It bundles the following components.

| Component | Licence |
|---|---|
| `com.flyfishxu:kadb-android`, `com.flyfishxu:kadb-mdns-android` 2.1.3 | Apache-2.0 |
| **`com.github.Flyfish233:spake2-java` 1.1.1** | **GPL-3.0-only** |
| `cafe.cryptography:ed25519-elisabeth`, `curve25519-elisabeth` 0.1.0 | MIT |
| `asia.hombre:kyber`, `asia.hombre:keccak` 2.0.1 | Apache-2.0 |
| `org.bouncycastle:bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on` | MIT |
| AndroidX, Jetpack Compose, `androidx.tv:tv-material` | Apache-2.0 |
| Kotlin standard library, `kotlinx-coroutines` | Apache-2.0 |

## The APK and the GPL

`spake2-java` (https://github.com/Flyfish233/spake2-java) is published under the
**GNU General Public License, version 3**. It arrives as a transitive dependency
of `kadb-android` and implements the SPAKE2 exchange that Android 11+ wireless
debugging uses to pair with a six-digit code — without it the application cannot
pair with a modern device at all.

Because that code is linked into the APK, the APK as a combined work is
distributed under the **GPL-3.0**, and a copy of its text ships with every
release as `LICENSE-GPL-3.0.txt`. The source code of this project stays under
the Apache License 2.0, which the GPL-3.0 permits to be combined in this
direction; the desktop executables are unaffected, as `spake2-java` is not part
of them.

The complete corresponding source of the APK is this repository at the tag
matching the release, plus the published sources of the dependencies listed
above. To rebuild it:

```bash
git clone https://github.com/civisrom/android-tv-date-time
cd android-tv-date-time && git checkout <release tag>
cd android && ./gradlew assembleRelease
```

The build is described entirely by `android/app/build.gradle.kts` and reproduced
automatically by `.github/workflows/build.yml`, so the result is equivalent to
the published APK apart from the signature.

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
