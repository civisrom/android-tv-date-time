import importlib.util
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
