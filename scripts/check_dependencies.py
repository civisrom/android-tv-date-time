#!/usr/bin/env python3
"""Check locked public dependency coordinates with OSV; never inspect local credentials."""
import argparse
from dataclasses import dataclass
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, help="Write a local CycloneDX dependency inventory (no binaries or credentials)")
    args = parser.parse_args()
    packages = dependencies(Path(__file__).resolve().parents[1])
    if args.sbom:
        args.sbom.parent.mkdir(parents=True, exist_ok=True)
        args.sbom.write_text(json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
            "components": [{"type": "library", "name": d.name, "version": d.version,
                "properties": [{"name": "ecosystem", "value": d.ecosystem},
                               {"name": "usage", "value": d.scope}]} for d in packages]}, indent=2) + "\n")
    findings = audit(packages)
    for dep, identifier in findings:
        print(f"{dep.ecosystem} {dep.name} {dep.version} ({dep.scope}): https://osv.dev/vulnerability/{identifier}")
    print(f"OSV: {len(packages)} dependency versions checked, {len(findings)} findings.")
    print("Scope: locked Python packages and verified Maven artifacts, including test/build tools; not a binary SBOM.")
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"Dependency check incomplete: {type(error).__name__}", file=sys.stderr)
        sys.exit(2)
