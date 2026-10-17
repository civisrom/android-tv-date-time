import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("audit_bundle", ROOT / "scripts/audit_bundle.py")
bundle = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bundle
spec.loader.exec_module(bundle)


class BundleAuditTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.workpath = self.base / "work"
        self.runtime = self.base / "python"
        self.wheel = self.base / "site-packages/pkg/_native.so"
        self.owner = bundle.osv.Dependency("PyPI", "pkg", "1.0", "bundled")

    def classify(self, path, system="linux", packages=None):
        return bundle.classify(Path(path), self.workpath, {self.wheel: self.owner}, {self.runtime},
                               packages or {}, system=system)

    def test_every_source_category_is_recognised(self):
        self.assertEqual(("python package", self.owner), self.classify(self.wheel))
        self.assertEqual(("pyinstaller output", None), self.classify(self.workpath / "PYZ-00.pyz"))
        self.assertEqual(("python runtime", None), self.classify(self.runtime / "lib/libpython3.12.dylib"))
        self.assertEqual(("platform-tools", None), self.classify(bundle.ROOT / "resources/adb"))
        self.assertEqual(("project", None), self.classify(bundle.ROOT / "src/android_time_fixer.py"))
        self.assertEqual(("macos system", None), self.classify("/usr/lib/libz.1.dylib", system="darwin"))

    def test_linux_system_libraries_become_ubuntu_packages(self):
        package = bundle.osv.Dependency("Ubuntu:22.04:LTS", "openssl", "3.0.2-0ubuntu1.30", "bundled")
        path = Path("/lib/x86_64-linux-gnu/libssl.so.3")
        self.assertEqual(("ubuntu package", package), self.classify(path, packages={path: package}))

    def test_dpkg_owners_are_found_through_merged_usr_aliases_in_one_query(self):
        files = [Path("/lib/x86_64-linux-gnu/libcrypto.so.3"), Path("/usr/lib/x86_64-linux-gnu/libz.so.1")]
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            if args[1] == "-S":
                stdout = ("libssl3:amd64: /usr/lib/x86_64-linux-gnu/libcrypto.so.3\n"
                          "zlib1g:amd64: /lib/x86_64-linux-gnu/libz.so.1\n")
            else:
                stdout = {"libssl3:amd64": "openssl\t3.0.2-0ubuntu1.30", "zlib1g:amd64": "zlib\t1:1.2.11"}[args[-1]]
            return subprocess.CompletedProcess(args, 0, stdout, "")

        with patch.object(bundle.subprocess, "run", side_effect=run):
            packages = bundle.debian_packages(files, "Ubuntu:22.04:LTS")
        self.assertEqual("openssl", packages[files[0]].name)
        self.assertEqual("zlib", packages[files[1]].name)
        self.assertEqual(1, sum(call[1] == "-S" for call in calls))

    def test_reviewed_findings_hold_only_for_the_reviewed_files(self):
        gcc = bundle.osv.Dependency("Ubuntu:22.04:LTS", "gcc-12", "12.3.0", "bundled")
        self.assertIsNotNone(bundle.reviewed(gcc, "UBUNTU-CVE-2026-102010", {"libgcc_s.so.1"}))
        # The source-built adb adds libstdc++, which was reviewed with adb's sources.
        self.assertIsNotNone(bundle.reviewed(gcc, "UBUNTU-CVE-2026-102010", {"libgcc_s.so.1", "libstdc++.so.6"}))
        self.assertIsNone(bundle.reviewed(gcc, "UBUNTU-CVE-2026-102010", {"libgcc_s.so.1", "libasan.so.8"}))
        self.assertIsNone(bundle.reviewed(gcc, "UBUNTU-CVE-2099-0001", {"libgcc_s.so.1"}))
        protobuf = bundle.osv.Dependency("Ubuntu:22.04:LTS", "protobuf", "3.12.4", "bundled")
        self.assertIsNotNone(bundle.reviewed(protobuf, "UBUNTU-CVE-2022-3171", {"libprotobuf.so.23"}))
        self.assertIsNone(bundle.reviewed(protobuf, "UBUNTU-CVE-2022-3171", {"libprotoc.so.23"}))

    def test_only_the_adb_built_from_source_is_marked_for_its_libusb_audit(self):
        built = b"\x7fELF\0" + bundle.build_adb.ANDROID_TOOLS_VERSION.encode() + b"\0android-tools\0"
        for name, content, expected in [("adb", built, True), ("adb", b"\x7fELF\0" + b"37.0.1-14190925", False),
                                        ("adb", b"\x7fELF\0android-tools\0" + b"36.0.1", False),
                                        ("libusb.so", built, False)]:
            path = self.base / name
            path.write_bytes(content)
            with self.subTest(name=name, expected=expected):
                self.assertIs(expected, bundle.source_built_adb(path))

    def test_linux_arm64_bundles_must_carry_the_adb_built_from_source(self):
        self.assertTrue(bundle.requires_source_adb("linux", "aarch64"))
        self.assertTrue(bundle.requires_source_adb("linux", "ARM64"))
        for system, machine in [("linux", "x86_64"), ("darwin", "arm64"), ("win32", "ARM64")]:
            with self.subTest(system=system, machine=machine):
                self.assertFalse(bundle.requires_source_adb(system, machine))

    def test_unowned_files_never_pass_silently(self):
        for path, system in [("/opt/other/libfoo.so", "linux"), (bundle.ROOT / ".venv/lib/stray.so", "linux"),
                             ("/opt/homebrew/lib/libssl.3.dylib", "darwin"), (self.base / "site-packages/other.py", "win32")]:
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "no audited source"):
                self.classify(path, system=system)

    def test_toc_sources_collects_nested_entries_from_both_tables(self):
        self.workpath.mkdir()
        (self.workpath / "PKG-00.toc").write_text(repr(("x.pkg", {}, [("adb", "/repo/resources/adb", "BINARY"),
                                                                       ("opt", "", "OPTION")])), encoding="utf-8")
        (self.workpath / "PYZ-00.toc").write_text(repr(("x.pyz", [("json", "/py/lib/json/__init__.py", "PYMODULE")])),
                                                  encoding="utf-8")
        self.assertEqual({bundle._absolute("/repo/resources/adb"), bundle._absolute("/py/lib/json/__init__.py")},
                         bundle.toc_sources(self.workpath))
