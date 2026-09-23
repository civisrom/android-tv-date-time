Upstream licence reference set from Astral python-build-standalone 20260901.
CPython 3.12.14 is used to build the Windows and macOS executables.

These unmodified files cover the upstream distribution and optional libraries.
Their inclusion does not mean every upstream component is bundled in this app.
In particular, this app does not bundle Berkeley DB or OpenSSL 1.1.
PyInstaller collects only the Python modules and libraries needed by the app.
CPython, OpenSSL 3, zlib, libffi, bzip2, XZ/liblzma and Expat are covered here;
additional optional-library notices are retained as an upstream reference.
The build-tool project LICENSE applies to that project, not to CPython itself.

The install_only_stripped distribution omits upstream root licence files.
They are preserved separately here; SOURCES.json records exact source URLs
and SHA-256 hashes. The embedded runtime retains its PSF licensing.

The exact CPython 3.12.14 LICENSE and Doc/license.rst are also preserved as
LICENSE.cpython-3.12.14.txt and NOTICE.cpython-3.12.14.rst. They supplement the
older CPython reference notice in the standalone builder repository.
The latter document includes current acknowledgements for software incorporated
in CPython. Both texts are unmodified, with provenance in SOURCES.json.
