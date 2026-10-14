import importlib.util
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "build-wasm-web.py"
SPEC = importlib.util.spec_from_file_location("build_wasm_web", MODULE_PATH)
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


class FoojayMetadataTests(unittest.TestCase):
    def test_uses_sha256_checksum_uri_when_inline_checksum_is_missing(self):
        package = {
            "id": "a" * 32,
            "java_version": "21.0.12.1+1",
            "lib_c_type": "glibc",
        }
        details = {
            "direct_download_uri": "https://cdn.example.test/jdk.tar.gz",
            "checksum_uri": "https://cdn.example.test/jdk.tar.gz.sha256",
            "checksum": "",
            "checksum_type": "",
        }
        responses = [
            {"result": [package]},
            {"result": [details]},
        ]

        with patch.object(bootstrap, "get_json", side_effect=responses), patch.object(
            bootstrap, "get_text", return_value="b" * 64 + "  jdk.tar.gz\n"
        ):
            result = bootstrap.query_package("x64")

        self.assertEqual(result["id"], "a" * 32)
        self.assertEqual(result["sha256"], "b" * 64)
        self.assertEqual(result["java_version"], "21.0.12.1+1")

    def test_reports_missing_metadata_fields_by_name(self):
        package = {
            "id": "a" * 32,
            "java_version": "21.0.12.1+1",
            "lib_c_type": "glibc",
        }
        responses = [{"result": [package]}, {"result": [{"checksum": ""}]}]

        with patch.object(bootstrap, "get_json", side_effect=responses):
            with self.assertRaisesRegex(
                bootstrap.BuildError, "direct_download_uri"
            ):
                bootstrap.query_package("x64")

    def test_rejects_checksum_uri_without_https(self):
        with self.assertRaisesRegex(bootstrap.BuildError, "HTTPS"):
            bootstrap.checksum_from_details(
                {"checksum_uri": "http://cdn.example.test/checksum"}, ""
            )

    def test_accepts_inline_sha256(self):
        digest = "c" * 64
        self.assertEqual(
            bootstrap.checksum_from_details(
                {"checksum": digest, "checksum_type": "sha256"}, "d" * 32
            ),
            digest,
        )

    def test_rejects_archive_with_path_outside_extraction_root(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "unsafe.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                member = tarfile.TarInfo("../outside")
                member.size = 0
                archive.addfile(member)
            with self.assertRaisesRegex(bootstrap.BuildError, "越界路径"):
                bootstrap.verify_archive_paths(archive_path)

    def test_selects_latest_valid_glibc_package(self):
        packages = [
            {"id": "a" * 32, "java_version": "21.0.9+1", "lib_c_type": "glibc"},
            {"id": "b" * 32, "java_version": "21.0.12+1", "lib_c_type": "glibc"},
            {"id": "c" * 32, "java_version": "21.0.99+1", "lib_c_type": "musl"},
        ]

        self.assertEqual(bootstrap.select_packages(packages)[0]["id"], "b" * 32)


if __name__ == "__main__":
    unittest.main()
