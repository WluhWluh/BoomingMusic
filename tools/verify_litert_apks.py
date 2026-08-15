#!/usr/bin/env python3
"""Verify that downloadable LiteRT native payloads are absent from APKs."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import zipfile


VARIANTS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"}
FORBIDDEN_LIBRARIES = {
    "libLiteRt.so",
    "liblitert_jni.so",
    "libLiteRtClGlAccelerator.so",
    "libBssOcl.so",
    "libOCLQ.so",
}


def apk_variant(path: Path) -> str | None:
    for variant in VARIANTS:
        if path.stem.endswith(f"-{variant}"):
            return variant
    return None


def verify_apk(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        forbidden = sorted(
            item.filename for item in archive.infolist()
            if Path(item.filename).name in FORBIDDEN_LIBRARIES
        )
    if forbidden:
        raise RuntimeError(
            f"{path.name} packages downloadable LiteRT libraries: {forbidden}"
        )
    print(f"Verified no packaged LiteRT runtime: {path.name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk_directory", type=Path)
    args = parser.parse_args()
    try:
        paths: dict[str, Path] = {}
        for path in sorted(args.apk_directory.glob("*.apk")):
            variant = apk_variant(path)
            if variant is None:
                continue
            if variant in paths:
                raise RuntimeError(f"Multiple APKs found for {variant}")
            paths[variant] = path
        missing = VARIANTS - paths.keys()
        if missing:
            raise RuntimeError(f"Missing APK variants: {sorted(missing)}")
        for variant in sorted(VARIANTS):
            verify_apk(paths[variant])
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
