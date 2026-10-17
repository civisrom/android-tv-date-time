import importlib.util
import json
from pathlib import Path
import sys
import unittest

spec = importlib.util.spec_from_file_location("dependency_check", Path(__file__).resolve().parents[1] / "scripts/check_dependencies.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class DependencyCheckTests(unittest.TestCase):
    def test_pagination_preserves_package_and_scope(self):
        packages = [module.Dependency("Maven", "group:one", "1", "runtime"),
                    module.Dependency("PyPI", "two", "2", "development")]
        calls = []

        def request(queries):
            calls.append(queries)
            if len(calls) == 1:
                return {"results": [{"next_page_token": "next"}, {"vulns": [{"id": "GHSA-test-2"}]}]}
            return {"results": [{"vulns": [{"id": "GHSA-test-1"}]}]}

        self.assertEqual([(packages[0], "GHSA-test-1"), (packages[1], "GHSA-test-2")], module.audit(packages, request))
        self.assertEqual([{**packages[0].query(), "page_token": "next"}], calls[1])

    def test_incomplete_or_malformed_service_response_never_passes(self):
        packages = [module.Dependency("PyPI", "one", "1", "runtime")]
        for response in ({}, {"results": []}, {"results": [{"error": "failed"}]},
                         {"results": [{"vulns": "bad"}]}, {"results": [{"vulns": [{"id": "bad\nvalue"}]}]}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                module.audit(packages, lambda _: response)

    def test_no_known_vulnerabilities_is_a_complete_empty_result(self):
        packages = [module.Dependency("PyPI", "one", "1", "runtime")]
        self.assertEqual([], module.audit(packages, lambda _: {"results": [{}]}))

    @staticmethod
    def _osv(vulnerable):
        def request(queries):
            return {"results": [{"vulns": [{"id": "CVE-2099-0001"}]}
                                if (query["package"]["name"], query["version"]) in vulnerable else {}
                                for query in queries]}
        return request

    def test_native_audit_reports_runtime_findings_but_not_canaries(self):
        runtime = [module.openssl("3.5.9", "runtime"), module.cpython("3.12.15", "runtime")]
        canaries = {(dep.name, dep.version) for dep in module.CANARIES}
        self.assertEqual([], module.audit_native(runtime, self._osv(canaries)))
        vulnerable = canaries | {(module.OPENSSL_GIT, "openssl-3.5.9")}
        self.assertEqual([(runtime[0], "CVE-2099-0001")], module.audit_native(runtime, self._osv(vulnerable)))

    def test_native_audit_fails_when_osv_misses_any_known_vulnerable_canary(self):
        runtime = [module.openssl("3.5.9", "runtime")]
        canaries = {(dep.name, dep.version) for dep in module.CANARIES}
        for missing in canaries:
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "known vulnerable"):
                module.audit_native(runtime, self._osv(canaries - {missing}))

    def test_pinned_desktop_runtime_comes_from_the_build_python_script(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location("pinned", root / "scripts/setup_build_python.py")
        pinned = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pinned)
        native = module.pinned_native(root)
        self.assertEqual([module.cpython(pinned.CPYTHON_VERSION, "release"),
                          module.openssl(pinned.OPENSSL_VERSION, "release")], native[:2])
        self.assertIn(f"{pinned.RELEASE}/", pinned.RELEASE_URL)
        self.assertEqual(module.GRADLE_GIT, native[2].name)
        self.assertRegex(native[2].version, r"^v\d+\.\d+\.\d+$")
        wrapper = (root / "android/gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn(f"gradle-{native[2].version[1:].removesuffix('.0')}-bin.zip", wrapper)

    def test_build_python_components_must_match_the_pinned_release(self):
        root = Path(__file__).resolve().parents[1]
        pinned = module._load(root / "scripts/setup_build_python.py")
        minor = ".".join(pinned.CPYTHON_VERSION.split(".")[:2])
        downloads = {f"cpython-{minor}": {"version": pinned.CPYTHON_VERSION},
                     "openssl-3.5": {"version": pinned.OPENSSL_VERSION}, "expat": {"version": "2.9.0"},
                     "zlib": {"version": "1.3.2"}, "xz": {"version": "5.8.4"}, "bzip2": {"version": "1.0.8"},
                     "libffi": {"version": "3.8.0"}, "mpdecimal": {"version": "4.0.0"}}
        urls = []

        def request(url):
            urls.append(url)
            return json.dumps(downloads).encode()

        audited, without_feed = module.standalone_components(root, request)
        self.assertIn(module.Dependency("GIT", module.EXPAT_GIT, "R_2_9_0", "release"), audited)
        self.assertIn(module.Dependency("GIT", module.XZ_GIT, "v5.8.4", "release"), audited)
        self.assertEqual({"bzip2": "1.0.8", "libffi": "3.8.0", "mpdecimal": "4.0.0"}, without_feed)
        self.assertRegex(urls[0], r"/[0-9a-f]{40}/pythonbuild/downloads\.json$")
        downloads["openssl-3.5"] = {"version": "3.5.8"}
        with self.assertRaisesRegex(ValueError, "differ"):
            module.standalone_components(root, request)

    def test_platform_tools_pin_is_compared_with_the_stable_channel(self):
        root = Path(__file__).resolve().parents[1]
        xml = """<sdk:repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03">
          <remotePackage path="platform-tools"><revision><major>99</major><minor>0</minor><micro>0</micro></revision>
            <channelRef ref="channel-2"/></remotePackage>
          <remotePackage path="platform-tools"><revision><major>37</major><minor>0</minor><micro>2</micro></revision>
            <channelRef ref="channel-0"/></remotePackage></sdk:repository>"""
        pinned, latest = module.platform_tools(root, lambda _: xml.encode())
        self.assertEqual((37, 0, 2), latest)
        self.assertEqual(3, len(pinned))
