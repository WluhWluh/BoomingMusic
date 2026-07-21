#!/usr/bin/env python3
"""Verify LiteRT native-library inventory in ABI split and universal APKs."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys
import zipfile


X86_SHA256 = "02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af"
EXPECTED_BY_VARIANT = {
    "arm64-v8a": {
        "lib/arm64-v8a/libLiteRt.so",
        "lib/arm64-v8a/libLiteRtClGlAccelerator.so",
    },
    "armeabi-v7a": {"lib/armeabi-v7a/libLiteRt.so"},
    "x86_64": {
        "lib/x86_64/libLiteRt.so",
        "lib/x86_64/libLiteRtClGlAccelerator.so",
    },
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
        if entry.filename.startswith("lib/") and "/libLiteRt" in entry.filename
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
        if "lib/x86/libLiteRt.so" in actual:
            digest = hashlib.sha256(archive.read("lib/x86/libLiteRt.so")).hexdigest()
            if digest != X86_SHA256:
                raise RuntimeError(f"Unexpected x86 LiteRT SHA-256 in {path.name}: {digest}")
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
