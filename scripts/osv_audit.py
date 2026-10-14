"""Check resolved dependencies against the OSV database (osv.dev), as a cross-check of each ecosystem's own audit.

usage: python scripts/osv_audit.py <Maven|Go|PyPI|npm> <deps file>
deps file: Maven `mvn dependency:list -DoutputFile=...` output, or lines of "name version".
Exits 1 when any dependency has a known vulnerability.
"""

import json
import re
import sys
import urllib.request
from pathlib import Path

MAVEN = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w\-]+:(?:[\w.\-]+:)?([\w.\-]+):(compile|runtime|test|provided|system)")


def deps(eco: str, text: str) -> list[tuple[str, str]]:
    """Parse (name, version) pairs from the deps file."""
    out = []
    for line in text.splitlines():
        if eco == "Maven":
            m = MAVEN.match(line)
            if m:
                out.append((f"{m[1]}:{m[2]}", m[3]))
        elif line.strip() and not line.startswith("#"):
            name, version = line.split()[:2]
            out.append((name, version.lstrip("v") if eco != "Go" else version))
    return sorted(set(out))


def main() -> None:
    """Query OSV in one batch and print a verdict per dependency."""
    eco, path = sys.argv[1], Path(sys.argv[2])
    pkgs = deps(eco, path.read_text(encoding="utf-8"))
    body = {"queries": [{"package": {"name": n, "ecosystem": eco}, "version": v} for n, v in pkgs]}
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:  # noqa: S310 - fixed https URL
        results = json.load(r)["results"]
    bad = 0
    for (n, v), res in zip(pkgs, results, strict=True):
        ids = [x["id"] for x in res.get("vulns", [])]
        if ids:
            bad += 1
            print(f"VULNERABLE {n} {v}: {', '.join(ids)}")
    print(f"OSV ({eco}): {len(pkgs)} dependencies checked, {bad} with known vulnerabilities")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
