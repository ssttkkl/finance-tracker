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


class WebApiBuildConfigTests(unittest.TestCase):
    def test_production_build_forces_compose_demo_mode_off(self):
        environment = {"FT_COMPOSE_DEMO_MODE": "true"}

        bootstrap.disable_compose_demo_mode(environment)

        self.assertEqual(environment["FT_COMPOSE_DEMO_MODE"], "false")

    def test_configures_shared_buildkonfig_api_origin(self):
        environment = {"FT_API_ORIGIN": "https://finance-api.example.com"}

        api_origin = bootstrap.configure_web_api_origin(environment)

        self.assertEqual(api_origin, "https://finance-api.example.com")
        self.assertEqual(environment["FT_API_ORIGIN"], "https://finance-api.example.com")

    def test_empty_origin_defaults_to_local_backend(self):
        for empty_origin in ("", "   "):
            with self.subTest(origin=empty_origin):
                environment = {"FT_API_ORIGIN": empty_origin}
                self.assertEqual(
                    bootstrap.configure_web_api_origin(environment),
                    "http://127.0.0.1:8000",
                )
                self.assertEqual(environment["FT_API_ORIGIN"], "http://127.0.0.1:8000")

    def test_rejects_api_paths_and_non_https_origins(self):
        for invalid_origin in (
            "https://finance-api.example.com/api",
            "http://finance-api.example.com",
            "https://user:pass@finance-api.example.com",
        ):
            with self.subTest(origin=invalid_origin):
                with self.assertRaises(bootstrap.BuildError):
                    bootstrap.configure_web_api_origin({"FT_API_ORIGIN": invalid_origin})

    def test_rejects_invalid_port(self):
        with self.assertRaisesRegex(bootstrap.BuildError, "端口"):
            bootstrap.configure_web_api_origin(
                {"FT_API_ORIGIN": "https://finance-api.example.com:70000"}
            )
