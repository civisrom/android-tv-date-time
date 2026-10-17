#!/usr/bin/env python3
"""Check locked public dependency coordinates with OSV; never inspect local credentials."""
import argparse
from dataclasses import dataclass
import importlib.util
import json
from pathlib import Path
import re
import sys
import tomllib
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


@dataclass(frozen=True, order=True)
class Dependency:
    ecosystem: str
    name: str
    version: str
    scope: str

    def query(self):
        return {"package": {"ecosystem": self.ecosystem, "name": self.name}, "version": self.version}


def dependencies(root):
    result = set()
    lock = tomllib.loads((root / "poetry.lock").read_text())
    for package in lock["package"]:
        scope = "runtime" if "main" in package.get("groups", ["main"]) else "development"
        result.add(Dependency("PyPI", package["name"], package["version"], scope))
    configurations = {}
    for line in (root / "android/app/gradle.lockfile").read_text().splitlines():
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate, names = line.split("=", 1)
        configurations[coordinate] = names.split(",")
    metadata = ET.parse(root / "android/gradle/verification-metadata.xml")
    for component in metadata.findall(".//{*}component"):
        name = component.attrib["group"] + ":" + component.attrib["name"]
        version = component.attrib["version"]
        configs = configurations.get(name + ":" + version, [])
        scope = "build"
        if any("RuntimeClasspath" in c and "Test" not in c for c in configs):
            scope = "runtime"
        elif any("Test" in c for c in configs):
            scope = "test"
        result.add(Dependency("Maven", name, version, scope))
    if not any(d.ecosystem == "Maven" for d in result) or not any(d.ecosystem == "PyPI" for d in result):
        raise ValueError("Dependency inventory is incomplete")
    return sorted(result)


OPENSSL_GIT = "https://github.com/openssl/openssl"
CPYTHON_GIT = "https://github.com/python/cpython"
GRADLE_GIT = "https://github.com/gradle/gradle"
EXPAT_GIT = "https://github.com/libexpat/libexpat"
ZLIB_GIT = "https://github.com/madler/zlib"
XZ_GIT = "https://github.com/tukaani-project/xz"
UBUNTU_JAMMY = "Ubuntu:22.04:LTS"
# Native runtimes are not package-manager coordinates: OSV matches them by
# upstream release tag. A version OSV does not know also returns no findings,
# so each audit includes releases with published advisories and fails if they
# come back clean.
CANARIES = (Dependency("GIT", OPENSSL_GIT, "openssl-3.5.8", "canary"),
            Dependency("GIT", CPYTHON_GIT, "v3.12.14", "canary"),
            Dependency("GIT", GRADLE_GIT, "v7.6.0", "canary"),
            Dependency("GIT", EXPAT_GIT, "R_2_6_0", "canary"),
            Dependency("GIT", ZLIB_GIT, "v1.2.11", "canary"),
            Dependency("GIT", XZ_GIT, "v5.6.1", "canary"),
            Dependency(UBUNTU_JAMMY, "openssl", "3.0.2-0ubuntu1.29", "canary"))


def openssl(version, scope):
    return Dependency("GIT", OPENSSL_GIT, f"openssl-{version}", scope)


def cpython(version, scope):
    return Dependency("GIT", CPYTHON_GIT, f"v{version}", scope)


def pinned_native(root):
    pinned = _load(root / "scripts/setup_build_python.py")
    wrapper = (root / "android/gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    match = re.search(r"^distributionUrl=.*/gradle-(\d+)\.(\d+)(?:\.(\d+))?-bin\.zip$", wrapper, re.MULTILINE)
    if not match:
        raise ValueError("Gradle wrapper version is not recognised")
    gradle = ".".join(part or "0" for part in match.groups())
    return [cpython(pinned.CPYTHON_VERSION, "release"), openssl(pinned.OPENSSL_VERSION, "release"),
            Dependency("GIT", GRADLE_GIT, f"v{gradle}", "build")]


# Libraries of the pinned python-build-standalone release that ship in the
# executables, with the upstream tag format OSV matches.
STANDALONE_COMPONENTS = {
    "openssl-3.5": lambda v: Dependency("GIT", OPENSSL_GIT, f"openssl-{v}", "release"),
    "expat": lambda v: Dependency("GIT", EXPAT_GIT, "R_" + v.replace(".", "_"), "release"),
    "zlib": lambda v: Dependency("GIT", ZLIB_GIT, f"v{v}", "release"),
    "xz": lambda v: Dependency("GIT", XZ_GIT, f"v{v}", "release"),
}
# Also shipped, but OSV reports nothing even for releases with known CVEs.
STANDALONE_WITHOUT_FEED = ("bzip2", "libffi", "mpdecimal")


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": "TVTimeFixer-dependency-check"}), timeout=30) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024:
        raise ValueError("Response is too large")
    return data


def _load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def standalone_components(root, request=fetch):
    """Audited and feed-less libraries of the pinned build Python release."""
    pinned = _load(root / "scripts/setup_build_python.py")
    sources = json.loads((root / "licenses/python-standalone/SOURCES.json").read_text(encoding="utf-8"))
    if sources["release"] != pinned.RELEASE or not re.fullmatch(r"[0-9a-f]{40}", sources["commit"]):
        raise ValueError("Licence sources do not match the pinned build Python release")
    downloads = json.loads(request("https://raw.githubusercontent.com/astral-sh/python-build-standalone/"
                                   f"{sources['commit']}/pythonbuild/downloads.json"))
    minor = ".".join(pinned.CPYTHON_VERSION.split(".")[:2])
    if (downloads[f"cpython-{minor}"]["version"] != pinned.CPYTHON_VERSION
            or downloads["openssl-3.5"]["version"] != pinned.OPENSSL_VERSION):
        raise ValueError("Pinned CPython/OpenSSL versions differ from the build Python release")
    audited = [make(downloads[key]["version"]) for key, make in STANDALONE_COMPONENTS.items()]
    return audited, {key: downloads[key]["version"] for key in STANDALONE_WITHOUT_FEED}


def _revision(element):
    return tuple(int(element.findtext(f"{{*}}{part}") or element.findtext(part) or 0)
                 for part in ("major", "minor", "micro"))


def platform_tools(root, request=fetch):
    """Return the pinned and the latest stable Android Platform Tools revision."""
    workflows = "".join((root / ".github/workflows" / name).read_text(encoding="utf-8") for name in ("ci.yml", "build.yml"))
    pins = set(re.findall(r"platform-tools_r(\d+)\.(\d+)\.(\d+)-", workflows))
    if len(pins) != 1:
        raise ValueError("Workflows pin different or no Platform Tools revisions")
    repository = ET.fromstring(request("https://dl.google.com/android/repository/repository2-3.xml"))
    latest = [_revision(package.find("revision")) for package in repository.iter("remotePackage")
              if package.get("path") == "platform-tools" and package.find("channelRef") is not None
              and package.find("channelRef").get("ref") == "channel-0"]
    if not latest:
        raise ValueError("Platform Tools are missing from the Android repository")
    return tuple(map(int, pins.pop())), max(latest)


def post(queries):
    request = Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries}).encode(),
                      headers={"Content-Type": "application/json", "User-Agent": "TVTimeFixer-dependency-check"})
    with urlopen(request, timeout=30) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024:
        raise ValueError("OSV response is too large")
    return json.loads(data)


def audit(packages, request=post):
    findings = set()
    for start in range(0, len(packages), 100):
        pending = [(dep, dep.query()) for dep in packages[start:start + 100]]
        for _ in range(10):
            response = request([query for _, query in pending])
            results = response.get("results")
            if not isinstance(results, list) or len(results) != len(pending):
                raise ValueError("Incomplete OSV response")
            following = []
            for (dep, query), result in zip(pending, results):
                if not isinstance(result, dict) or "error" in result:
                    raise ValueError("OSV query failed")
                vulns = result.get("vulns", [])
                if not isinstance(vulns, list):
                    raise ValueError("Invalid OSV findings")
                for vuln in vulns:
                    identifier = vuln.get("id", "")
                    if not re.fullmatch(r"[A-Za-z0-9._-]+", identifier):
                        raise ValueError("Invalid OSV identifier")
                    findings.add((dep, identifier))
                token = result.get("next_page_token")
                if token:
                    following.append((dep, {**query, "page_token": token}))
            if not following:
                break
            pending = following
        else:
            raise ValueError("OSV pagination did not complete")
    return sorted(findings)


def audit_native(components, request=post):
    findings = audit(sorted(set(components) | set(CANARIES)), request)
    if not set(CANARIES) <= {dep for dep, _ in findings}:
        raise ValueError("OSV did not report advisories for known vulnerable native releases")
    return [(dep, identifier) for dep, identifier in findings if dep.scope != "canary"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, help="Write a local CycloneDX dependency inventory (no binaries or credentials)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    standalone, without_feed = standalone_components(root)
    packages = sorted(set(dependencies(root) + pinned_native(root) + standalone))
    if args.sbom:
        args.sbom.parent.mkdir(parents=True, exist_ok=True)
        args.sbom.write_text(json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "components": [{"type": "library", "name": d.name, "version": d.version,
                "properties": [{"name": "ecosystem", "value": d.ecosystem},
                               {"name": "usage", "value": d.scope}]} for d in packages]}, indent=2) + "\n")
    findings = audit_native(packages)
    for dep, identifier in findings:
        print(f"{dep.ecosystem} {dep.name} {dep.version} ({dep.scope}): https://osv.dev/vulnerability/{identifier}")
    print(f"OSV: {len(packages)} dependency versions checked, {len(findings)} findings.")
    print("No OSV advisory feed, review upstream manually:",
          ", ".join(f"{name} {version}" for name, version in without_feed.items()), "(build Python)")
    pinned_adb, latest_adb = platform_tools(root)
    adb = ".".join(map(str, pinned_adb))
    if pinned_adb < latest_adb:
        # No advisory feed exists for adb, so a newer release is a prompt to update, not a failure.
        print(f"::warning::Platform Tools r{adb} is older than r{'.'.join(map(str, latest_adb))}: "
              "update the workflow URLs and SHA-256.")
    else:
        print(f"Platform Tools r{adb} is the latest stable release (no OSV feed for adb).")
    print("Scope: locked Python packages, verified Maven artifacts including test/build tools, the Gradle "
          "wrapper and the pinned build Python with its bundled libraries. Shipped executables are audited "
          "file by file by audit_bundle.py.")
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"Dependency check incomplete: {type(error).__name__}", file=sys.stderr)
        sys.exit(2)
