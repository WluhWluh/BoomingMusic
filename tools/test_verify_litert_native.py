from __future__ import annotations

from contextlib import redirect_stdout
import io
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
import zipfile

from verify_litert_native import ARTIFACT_VERSION, verify, verify_x86_runtime


ROOT = Path(__file__).resolve().parents[1]
PINNED_RUNTIME = (
    Path.home()
    / ".gradle/caches/booming-ss/litert"
    / ARTIFACT_VERSION
    / f"litert-android-{ARTIFACT_VERSION}.aar"
)


class VerifyLiteRtNativeTest(unittest.TestCase):
    def test_pinned_runtime_aar_passes(self) -> None:
        with redirect_stdout(io.StringIO()):
            verify(PINNED_RUNTIME)

    def test_missing_runtime_fails_locally(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "litert.aar"
            with self.assertRaisesRegex(RuntimeError, "AAR is missing"):
                verify(missing)

    def test_same_size_tampered_aar_fails_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tampered = Path(directory) / "litert.aar"
            shutil.copyfile(PINNED_RUNTIME, tampered)
            data = bytearray(tampered.read_bytes())
            data[7] ^= 1
            tampered.write_bytes(data)

            with self.assertRaisesRegex(RuntimeError, "Unexpected bounded LiteRT AAR SHA-256"):
                verify(tampered)

    def test_wrong_machine_runtime_fails_architecture(self) -> None:
        with zipfile.ZipFile(PINNED_RUNTIME) as archive:
            wrong_machine = bytearray(archive.read("jni/x86/libLiteRt.so"))
        struct.pack_into("<H", wrong_machine, 18, 40)

        with self.assertRaisesRegex(
            RuntimeError,
            "ELF machine is 40, expected Intel 80386",
        ):
            verify_x86_runtime(bytes(wrong_machine))


if __name__ == "__main__":
    unittest.main()
