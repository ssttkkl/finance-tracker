import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "mobile-ci.yml"
README = ROOT / "mobile" / "README.md"
VALIDATOR = ROOT / "mobile" / "scripts" / "validate-build-time-api-origin.mjs"
ANDROID_SIGNING_PLUGIN = ROOT / "mobile" / "plugins" / "withAndroidCiTestSigning.js"
TEST_KEYSTORE = ROOT / "mobile" / "ci" / "finance-tracker-test.keystore"


def test_mobile_ci_builds_release_like_artifacts_with_embedded_js():
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "EXPO_PUBLIC_FT_API_ORIGIN: ${{ vars.EXPO_PUBLIC_FT_API_ORIGIN }}" in workflow
    assert workflow.count("Validate build-time API origin") == 3
    assert workflow.count("node mobile/scripts/validate-build-time-api-origin.mjs") == 3
    assert "assembleRelease" in workflow
    assert "-configuration Release" in workflow
    assert "Assemble test-signed Release APK with embedded JavaScript" in workflow
    assert "finance-tracker-test.keystore" in workflow
    assert "apksigner verify --verbose --print-certs" in workflow
    assert workflow.count("FT_TEST_KEYSTORE_PASSWORD") >= 2
    assert "secrets." not in workflow
    assert "NODE_ENV=production npm run export:android" in workflow
    assert "NODE_ENV=production npm run export:ios" in workflow
    assert workflow.count("NODE_ENV: production") == 2
    assert "finance-tracker-android-release" in workflow
    assert "finance-tracker-ios-simulator-release" in workflow
    assert "assembleDebug" not in workflow


def test_mobile_ci_docs_describe_variable_and_standalone_artifacts():
    readme = README.read_text(encoding="utf-8")

    assert "EXPO_PUBLIC_FT_API_ORIGIN" in readme
    assert "finance-tracker-android-release" in readme
    assert "finance-tracker-ios-simulator-release" in readme
    assert "Metro" in readme
    assert "未签名" in readme
    assert "finance-tracker-test.keystore" in readme
    assert "finance-tracker-test" in readme


def test_android_ci_signing_is_a_committed_non_production_test_boundary():
    plugin = ANDROID_SIGNING_PLUGIN.read_text(encoding="utf-8")

    assert TEST_KEYSTORE.is_file()
    assert "finance-tracker-test.keystore" in plugin
    assert "signingConfigs.ciTest" in plugin
    assert "FT_TEST_KEYSTORE_PASSWORD" in plugin
    assert "FT_TEST_KEY_ALIAS" in plugin
    assert "FT_TEST_KEY_PASSWORD" in plugin


def test_build_time_api_origin_validator_fails_closed_without_logging_the_value():
    def run(value: str | None) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        if value is None:
            environment.pop("EXPO_PUBLIC_FT_API_ORIGIN", None)
        else:
            environment["EXPO_PUBLIC_FT_API_ORIGIN"] = value
        return subprocess.run(
            ["node", str(VALIDATOR)],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

    assert run("https://api.example.com/").returncode == 0
    invalid = run("http://api.example.com/private")
    assert invalid.returncode != 0
    assert "private" not in invalid.stderr
    assert run(None).returncode != 0
