from __future__ import annotations

from contextlib import redirect_stdout
import io
from pathlib import Path
import shutil
import tempfile
import unittest

from verify_litert_native import API_VERSION, verify


ROOT = Path(__file__).resolve().parents[1]
PINNED_API = (
    Path.home()
    / ".gradle/caches/booming-ss/litert/api"
    / API_VERSION
    / f"litert-api-{API_VERSION}.aar"
)


class VerifyLiteRtNativeTest(unittest.TestCase):
    def test_pinned_downloadable_api_and_catalogs_pass(self) -> None:
        with redirect_stdout(io.StringIO()):
            verify(PINNED_API, ROOT)

    def test_missing_api_fails_locally(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "litert-api.aar"
            with self.assertRaisesRegex(RuntimeError, "API AAR is missing"):
                verify(missing, ROOT)

    def test_same_size_tampered_api_fails_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tampered = Path(directory) / "litert-api.aar"
            shutil.copyfile(PINNED_API, tampered)
            data = bytearray(tampered.read_bytes())
            data[7] ^= 1
            tampered.write_bytes(data)
            with self.assertRaisesRegex(RuntimeError, "API AAR SHA-256"):
                verify(tampered, ROOT)


if __name__ == "__main__":
    unittest.main()
