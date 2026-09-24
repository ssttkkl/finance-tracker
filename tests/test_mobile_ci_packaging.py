from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "mobile-ci.yml"
README = ROOT / "mobile" / "README.md"
ANDROID_SIGNING_PLUGIN = ROOT / "mobile" / "plugins" / "withAndroidCiTestSigning.js"
TEST_KEYSTORE = ROOT / "mobile" / "ci" / "finance-tracker-test.keystore"


def test_mobile_ci_builds_release_like_artifacts_with_embedded_js():
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert 'EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED: "1"' in workflow
    assert "EXPO_PUBLIC_FT_API_ORIGIN:" not in workflow
    assert "Validate build-time API origin" not in workflow
    assert "validate-build-time-api-origin.mjs" not in workflow
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
    assert "-sdk iphoneos" in workflow
    assert "-destination 'generic/platform=iOS'" in workflow
    assert "Release-iphoneos" in workflow
    assert "CODE_SIGNING_ALLOWED=NO" in workflow
    assert "CODE_SIGNING_REQUIRED=NO" in workflow
    assert 'CODE_SIGN_IDENTITY=""' in workflow
    assert "Payload" in workflow
    assert "finance-tracker-ios-device-release-unsigned.ipa" in workflow
    assert "finance-tracker-ios-device-release-unsigned" in workflow
    assert "iphonesimulator" not in workflow
    assert "ios-simulator" not in workflow
    assert "assembleDebug" not in workflow


def test_mobile_ci_docs_describe_login_origin_and_standalone_artifacts():
    readme = README.read_text(encoding="utf-8")

    assert "EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED" in readme
    assert "登录" in readme
    assert "后端地址" in readme
    assert "HTTP" in readme
    assert "finance-tracker-android-release" in readme
    assert "finance-tracker-ios-device-release" in readme
    assert "真机" in readme
    assert ".ipa" in readme
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


def test_mobile_ci_does_not_require_a_build_time_api_origin_validator():
    assert not (ROOT / "mobile" / "scripts" / "validate-build-time-api-origin.mjs").exists()
