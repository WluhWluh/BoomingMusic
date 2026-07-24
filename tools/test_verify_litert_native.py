from __future__ import annotations

from contextlib import redirect_stdout
import io
from pathlib import Path
import shutil
import struct
import tempfile
import unittest

from verify_litert_native import verify


ROOT = Path(__file__).resolve().parents[1]
PINNED_RUNTIME = ROOT / "app" / "src" / "main" / "jniLibs" / "x86" / "libLiteRt.so"


class VerifyLiteRtNativeTest(unittest.TestCase):
    def test_pinned_runtime_passes(self) -> None:
        with redirect_stdout(io.StringIO()):
            verify(PINNED_RUNTIME)

    def test_missing_runtime_fails_locally(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "libLiteRt.so"
            with self.assertRaisesRegex(RuntimeError, "library is missing"):
                verify(missing)

    def test_same_size_tampered_runtime_fails_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tampered = Path(directory) / "libLiteRt.so"
            shutil.copyfile(PINNED_RUNTIME, tampered)
            data = bytearray(tampered.read_bytes())
            data[7] ^= 1
            tampered.write_bytes(data)

            with self.assertRaisesRegex(RuntimeError, "Unexpected LiteRT x86 SHA-256"):
                verify(tampered)

    def test_wrong_machine_runtime_fails_architecture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            wrong_machine = Path(directory) / "libLiteRt.so"
            shutil.copyfile(PINNED_RUNTIME, wrong_machine)
            with wrong_machine.open("r+b") as output:
                output.seek(18)
                output.write(struct.pack("<H", 40))

            with self.assertRaisesRegex(
                RuntimeError,
                "ELF machine is 40, expected Intel 80386",
            ):
                verify(wrong_machine)


if __name__ == "__main__":
    unittest.main()
