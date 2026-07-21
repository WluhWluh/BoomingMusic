#!/usr/bin/env python3
"""Verify the pinned supplemental Android x86 LiteRT shared library."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import struct
import sys


EXPECTED_BYTES = 7_482_132
EXPECTED_SHA256 = "02b6556ec235926c11eb0c067eb16e459adcddb1568a42eefe0c40f4cc4b59af"
EXPECTED_DEPENDENCIES = {
    "libandroid.so",
    "libc.so",
    "libdl.so",
    "liblog.so",
    "libm.so",
}
REQUIRED_SYMBOLS = {
    "Java_com_google_ai_edge_litert_Environment_nativeCreate",
    "Java_com_google_ai_edge_litert_CompiledModel_nativeRun",
    "Java_com_google_ai_edge_litert_TensorBuffer_nativeReadFloat",
    "LiteRtCreateModelFromBuffer",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Elf32:
    SHT_DYNAMIC = 6
    SHT_DYNSYM = 11
    DT_NULL = 0
    DT_NEEDED = 1

    def __init__(self, data: bytes) -> None:
        self.data = data
        if data[:4] != b"\x7fELF":
            raise RuntimeError("File is not ELF")
        if data[4] != 1:
            raise RuntimeError("ELF class is not ELF32")
        if data[5] != 1:
            raise RuntimeError("ELF data is not little endian")
        if len(data) < 52:
            raise RuntimeError("ELF header is truncated")
        self.elf_type, self.machine = struct.unpack_from("<HH", data, 16)
        section_offset = struct.unpack_from("<I", data, 32)[0]
        section_entry_size, section_count = struct.unpack_from("<HH", data, 46)
        if section_entry_size < 40 or section_count <= 0:
            raise RuntimeError("ELF section table is invalid")
        self.sections: list[tuple[int, ...]] = []
        for index in range(section_count):
            offset = section_offset + index * section_entry_size
            if offset + 40 > len(data):
                raise RuntimeError("ELF section table is truncated")
            self.sections.append(struct.unpack_from("<IIIIIIIIII", data, offset))

    def section_data(self, section: tuple[int, ...]) -> bytes:
        offset, size = section[4], section[5]
        end = offset + size
        if end > len(self.data):
            raise RuntimeError("ELF section payload is truncated")
        return self.data[offset:end]

    @staticmethod
    def string(table: bytes, offset: int) -> str:
        if offset < 0 or offset >= len(table):
            raise RuntimeError("ELF string offset is invalid")
        end = table.find(b"\0", offset)
        if end < 0:
            raise RuntimeError("ELF string is not terminated")
        return table[offset:end].decode("utf-8", errors="replace")

    def linked_strings(self, section: tuple[int, ...]) -> bytes:
        link = section[6]
        if link >= len(self.sections):
            raise RuntimeError("ELF section string-table link is invalid")
        return self.section_data(self.sections[link])

    def dependencies(self) -> set[str]:
        dependencies: set[str] = set()
        for section in self.sections:
            if section[1] != self.SHT_DYNAMIC:
                continue
            payload = self.section_data(section)
            strings = self.linked_strings(section)
            entry_size = section[9] or 8
            if entry_size < 8:
                raise RuntimeError("ELF dynamic entry size is invalid")
            for offset in range(0, len(payload) - 7, entry_size):
                tag, value = struct.unpack_from("<II", payload, offset)
                if tag == self.DT_NULL:
                    break
                if tag == self.DT_NEEDED:
                    dependencies.add(self.string(strings, value))
        return dependencies

    def dynamic_symbols(self) -> set[str]:
        symbols: set[str] = set()
        for section in self.sections:
            if section[1] != self.SHT_DYNSYM:
                continue
            payload = self.section_data(section)
            strings = self.linked_strings(section)
            entry_size = section[9] or 16
            if entry_size < 16:
                raise RuntimeError("ELF symbol entry size is invalid")
            for offset in range(0, len(payload) - 15, entry_size):
                name_offset = struct.unpack_from("<I", payload, offset)[0]
                if name_offset:
                    symbols.add(self.string(strings, name_offset))
        return symbols


def verify(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"LiteRT x86 library is missing: {path}")
    if path.stat().st_size != EXPECTED_BYTES:
        raise RuntimeError(
            f"Unexpected LiteRT x86 size: {path.stat().st_size}, expected {EXPECTED_BYTES}"
        )
    actual_hash = sha256(path)
    if actual_hash != EXPECTED_SHA256:
        raise RuntimeError(f"Unexpected LiteRT x86 SHA-256: {actual_hash}")

    elf = Elf32(path.read_bytes())
    if elf.elf_type != 3:
        raise RuntimeError(f"ELF type is {elf.elf_type}, expected ET_DYN (3)")
    if elf.machine != 3:
        raise RuntimeError(f"ELF machine is {elf.machine}, expected Intel 80386 (3)")

    dependencies = elf.dependencies()
    if dependencies != EXPECTED_DEPENDENCIES:
        raise RuntimeError(
            "Unexpected DT_NEEDED set: "
            f"{sorted(dependencies)}; expected {sorted(EXPECTED_DEPENDENCIES)}"
        )
    if any(re.search(r"(EGL|GLES|OpenCL|vulkan)", item, re.IGNORECASE) for item in dependencies):
        raise RuntimeError("CPU-only x86 runtime depends on a GPU library")

    symbols = elf.dynamic_symbols()
    missing_symbols = sorted(REQUIRED_SYMBOLS - symbols)
    if missing_symbols:
        raise RuntimeError(f"Required LiteRT exports are missing: {missing_symbols}")

    print(f"Verified {path}")
    print(f"SHA-256: {actual_hash}")
    print(f"DT_NEEDED: {' '.join(sorted(dependencies))}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "library",
        nargs="?",
        default="app/src/main/jniLibs/x86/libLiteRt.so",
        type=Path,
    )
    args = parser.parse_args()
    try:
        verify(args.library.resolve())
    except (OSError, RuntimeError, struct.error) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
