#!/usr/bin/env python3
"""Verify LiteRT native-library inventory in ABI split and universal APKs."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys
import zipfile


NATIVE_SHA256 = {
    "lib/arm64-v8a/libBssOcl.so":
        "fc7deeb3081dda82cffbdc6d1bd8460c0491bbbc48478b781028e9413e8cb6f8",
    "lib/arm64-v8a/libLiteRt.so":
        "ae2b996fde27021b070e88b56eebc9626a5261feb72f09791bdac38b2f09abd2",
    "lib/arm64-v8a/libLiteRtClGlAccelerator.so":
        "83f2be273fdc0391ad977c8889d65ba6b947c3ebabc432bf51829e5c9e91f93c",
    "lib/armeabi-v7a/libLiteRt.so":
        "836ee7a2321c9453f02658b6774fc4c5951716432b450ba6bc4e9a94fe524e6c",
    "lib/x86/libLiteRt.so":
        "02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af",
    "lib/x86_64/libLiteRt.so":
        "6d5b2f35d536a3b2d38b26d26328cc9c259133ef2aa0413ec554cd7ef84f6604",
}
EXPECTED_BY_VARIANT = {
    "arm64-v8a": {
        "lib/arm64-v8a/libBssOcl.so",
        "lib/arm64-v8a/libLiteRt.so",
        "lib/arm64-v8a/libLiteRtClGlAccelerator.so",
    },
    "armeabi-v7a": {"lib/armeabi-v7a/libLiteRt.so"},
    "x86_64": {"lib/x86_64/libLiteRt.so"},
    "x86": {"lib/x86/libLiteRt.so"},
}
EXPECTED_UNIVERSAL = set().union(*EXPECTED_BY_VARIANT.values())


def apk_variant(path: Path) -> str | None:
    stem = path.stem
    for variant in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"):
        if stem.endswith(f"-{variant}"):
            return variant
    return None


def litert_entries(archive: zipfile.ZipFile) -> list[str]:
    return [
        entry.filename
        for entry in archive.infolist()
        if entry.filename in NATIVE_SHA256
    ]


def verify_apk(path: Path, variant: str) -> None:
    expected = EXPECTED_UNIVERSAL if variant == "universal" else EXPECTED_BY_VARIANT[variant]
    with zipfile.ZipFile(path) as archive:
        entries = litert_entries(archive)
        if len(entries) != len(set(entries)):
            raise RuntimeError(f"{path.name} contains duplicate LiteRT ZIP entries")
        actual = set(entries)
        if actual != expected:
            raise RuntimeError(
                f"Unexpected LiteRT inventory in {path.name}: {sorted(actual)}; "
                f"expected {sorted(expected)}"
            )
        for name in actual:
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            if digest != NATIVE_SHA256[name]:
                raise RuntimeError(
                    f"Unexpected SHA-256 for {name} in {path.name}: {digest}"
                )
    print(f"Verified {path.name}: {', '.join(sorted(expected))}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_directory", type=Path)
    args = parser.parse_args()
    try:
        paths_by_variant: dict[str, Path] = {}
        for path in sorted(args.apk_directory.glob("*.apk")):
            variant = apk_variant(path)
            if variant:
                if variant in paths_by_variant:
                    raise RuntimeError(f"Multiple APKs found for {variant}")
                paths_by_variant[variant] = path
        expected_variants = set(EXPECTED_BY_VARIANT) | {"universal"}
        missing = expected_variants - paths_by_variant.keys()
        if missing:
            raise RuntimeError(f"Missing APK variants: {sorted(missing)}")
        for variant in sorted(expected_variants):
            verify_apk(paths_by_variant[variant], variant)
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
