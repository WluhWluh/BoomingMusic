#!/usr/bin/env python3
"""Verify the downloadable LiteRT API and runtime catalog contract."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile


API_VERSION = "2.1.5-bss.2-downloadable-loader"
EXPECTED_API_BYTES = 86_013
EXPECTED_API_SHA256 = "a68b51546f268b6db0b64bec3d1d95389ba44a48c59beaa1769794682c94b4f9"
EXPECTED_API_ENTRIES = {
    "AndroidManifest.xml",
    "classes.jar",
    "META-INF/bss-litert/downloadable-api-source-lock.json",
    "META-INF/bss-litert/downloadable-runtime-contract.json",
}
EXPECTED_CLASSES_SHA256 = "e9bee2c5bd8e017e7d2e1a6cf7b6c1c0fe84568d2d4744b18eb2139ede181fab"
EXPECTED_SOURCE_LOCK_SHA256 = "f9a08269daa63e11525c94d30a581e1d09f54a0c35a83180c7765e3f73867fca"
EXPECTED_CONTRACT_SHA256 = "8ef393d5aa72ac03b77dc116531f6923bc826f2413e90f88eaf56128607c863a"
CONTRACT_PATH = "META-INF/bss-litert/downloadable-runtime-contract.json"
SOURCE_LOCK_PATH = "META-INF/bss-litert/downloadable-api-source-lock.json"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def require_equal(actual: object, expected: object, label: str) -> None:
    if actual != expected:
        raise RuntimeError(f"Unexpected {label}: {actual!r}; expected {expected!r}")


def verify_api(path: Path) -> tuple[dict, str]:
    if not path.is_file():
        raise RuntimeError(f"LiteRT API AAR is missing: {path}")
    require_equal(path.stat().st_size, EXPECTED_API_BYTES, "LiteRT API AAR size")
    require_equal(sha256(path), EXPECTED_API_SHA256, "LiteRT API AAR SHA-256")

    with zipfile.ZipFile(path) as archive:
        entries = {item.filename for item in archive.infolist() if not item.is_dir()}
        require_equal(entries, EXPECTED_API_ENTRIES, "LiteRT API AAR inventory")
        if any(item.startswith("jni/") or item.endswith(".so") for item in entries):
            raise RuntimeError("LiteRT API AAR must not contain native libraries")
        classes = archive.read("classes.jar")
        source_lock = archive.read(SOURCE_LOCK_PATH)
        contract_bytes = archive.read(CONTRACT_PATH)

    require_equal(digest(classes), EXPECTED_CLASSES_SHA256, "classes.jar SHA-256")
    require_equal(digest(source_lock), EXPECTED_SOURCE_LOCK_SHA256, "source lock SHA-256")
    require_equal(digest(contract_bytes), EXPECTED_CONTRACT_SHA256, "runtime contract SHA-256")
    source = json.loads(source_lock)
    contract = json.loads(contract_bytes)
    require_equal(source["loaderApi"]["className"],
                  "com.google.ai.edge.litert.LiteRtNativeLibraryLoader",
                  "explicit loader class")
    require_equal(contract["schemaVersion"], "bss-litert-downloadable-runtime-v2",
                  "runtime contract schema")
    require_equal(contract["androidMinApi"], 26, "runtime minimum API")
    return contract, digest(contract_bytes)


def load_catalog(path: Path) -> dict:
    if not path.is_file():
        raise RuntimeError(f"LiteRT runtime catalog is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def verify_catalogs(root: Path, contract: dict, contract_sha256: str) -> None:
    asset_root = root / "app/src/main/assets/source-separation"
    cpu = load_catalog(asset_root / "litert-runtime-catalog-v1.json")
    gpu = load_catalog(asset_root / "litert-gpu-runtime-catalog-v1.json")
    for name, catalog in (("CPU", cpu), ("GPU", gpu)):
        require_equal(catalog["producerContractSchemaVersion"], contract["schemaVersion"],
                      f"{name} catalog contract schema")
        require_equal(catalog["producerContractSha256"], contract_sha256,
                      f"{name} catalog contract SHA-256")

    expected_abis = contract["cpuCore"]["abis"]
    entries_by_abi = {entry["abi"]: entry for entry in cpu["entries"]}
    require_equal(set(entries_by_abi), set(expected_abis), "CPU catalog ABI set")
    for abi, expected in expected_abis.items():
        entry = entries_by_abi[abi]
        library = entry["innerLibrary"]
        require_equal(entry["runtimeArtifactVersion"], contract["runtimeArtifactVersion"],
                      f"{abi} runtime version")
        require_equal(entry["androidMinApi"], contract["androidMinApi"],
                      f"{abi} minimum API")
        require_equal(entry["delivery"]["artifactId"],
                      expected["bundleFileName"].removesuffix(".zip"),
                      f"{abi} delivery artifact")
        for key in ("byteSize", "sha256", "elfClass", "machine", "soname"):
            require_equal(library[key], expected[key], f"{abi} {key}")

    gpu_entries = gpu["entries"]
    require_equal(len(gpu_entries), 1, "GPU catalog entry count")
    gpu_entry = gpu_entries[0]
    expected_gpu = contract["boundedGpu"]
    require_equal(gpu_entry["abi"], expected_gpu["abi"], "GPU ABI")
    require_equal(gpu_entry["delivery"]["artifactId"],
                  expected_gpu["bundleFileName"].removesuffix(".zip"),
                  "GPU delivery artifact")
    require_equal(gpu_entry["requiredCpuLibrarySha256"],
                  expected_gpu["requiredCoreSha256"], "GPU required CPU SHA-256")
    require_equal(gpu_entry["capability"], expected_gpu["profile"], "GPU profile")
    expected_files = {item["path"]: item for item in expected_gpu["files"]}
    actual_files = {item["path"]: item for item in gpu_entry["files"]}
    require_equal(set(actual_files), set(expected_files), "GPU file set")
    for path, expected in expected_files.items():
        for key in ("byteSize", "sha256", "elfClass", "machine", "soname"):
            require_equal(actual_files[path][key], expected[key], f"{path} {key}")


def verify(path: Path, root: Path) -> None:
    contract, contract_sha256 = verify_api(path)
    verify_catalogs(root, contract, contract_sha256)
    print(f"Verified downloadable LiteRT API: {path}")
    print(f"API SHA-256: {EXPECTED_API_SHA256}")
    print("Verified CPU ABIs: arm64-v8a armeabi-v7a x86_64 x86")
    print("Verified bounded GPU profile: gpu-opencl-bounded-fp32-v1")


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "library",
        nargs="?",
        default=(Path.home() / ".gradle/caches/booming-ss/litert/api" / API_VERSION /
                 f"litert-api-{API_VERSION}.aar"),
        type=Path,
    )
    parser.add_argument("--root", default=root, type=Path)
    args = parser.parse_args()
    try:
        verify(args.library.resolve(), args.root.resolve())
    except (KeyError, OSError, RuntimeError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
