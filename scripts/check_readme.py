#!/usr/bin/env python3
"""The README's Java samples must not name a type the SDK does not define.

The samples are fragments, not compilable classes, so they cannot be handed to
javac. What actually goes stale, though, is narrow and checkable: a sample
importing a class that was renamed or removed. So this reads the public types
the source declares and compares every `com.guardiano3.sdk.X` and
`com.guardiano3.generated...X` a sample names against them.

It catches a name that does not exist, not a signature that changed.
Standard library only.

    python3 scripts/check_readme.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FENCE = re.compile(r"^```java\s*\n(.*?)^```", re.MULTILINE | re.DOTALL)
DECLARATION = re.compile(r"public (?:final |abstract )?(?:class|interface|enum) (\w+)")
REFERENCE = re.compile(r"\b(com\.guardiano3\.(?:sdk|generated(?:\.\w+)?))\.([A-Z]\w*)")

PACKAGE_DIRS = {
    "com.guardiano3.sdk": ROOT / "core/src/main/java/com/guardiano3/sdk",
    "com.guardiano3.generated": ROOT / "generated/src/main/java/com/guardiano3/generated",
    "com.guardiano3.generated.api": ROOT / "generated/src/main/java/com/guardiano3/generated/api",
    "com.guardiano3.generated.model": ROOT / "generated/src/main/java/com/guardiano3/generated/model",
}


def declared(package: str) -> set[str]:
    directory = PACKAGE_DIRS.get(package)
    if directory is None or not directory.is_dir():
        return set()
    names: set[str] = set()
    for path in directory.glob("*.java"):
        names |= set(DECLARATION.findall(path.read_text(encoding="utf-8")))
    return names


def main() -> int:
    samples = FENCE.findall((ROOT / "README.md").read_text(encoding="utf-8"))
    if not samples:
        print("README.md has no Java samples to check", file=sys.stderr)
        return 1

    failures = []
    for number, code in enumerate(samples, 1):
        for package, name in sorted(set(REFERENCE.findall(code))):
            if name not in declared(package):
                failures.append(f"README Java sample {number}: {package}.{name} does not exist")

    for failure in failures:
        print(failure, file=sys.stderr)
    if not failures:
        print(f"{len(samples)} Java samples name only types the SDK defines.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
