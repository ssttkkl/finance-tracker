from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_WORKFLOW = ROOT / ".github" / "workflows" / "compose-ci.yml"
LEGACY_MOBILE_WORKFLOW = ROOT / ".github" / "workflows" / "mobile-ci.yml"
LEGACY_MOBILE_README = ROOT / "mobile" / "README.md"
LEGACY_SIGNING_PLUGIN = ROOT / "mobile" / "plugins" / "withAndroidCiTestSigning.js"
LEGACY_TEST_KEYSTORE = ROOT / "mobile" / "ci" / "finance-tracker-test.keystore"


def test_compose_ci_uses_checked_in_wrapper_for_android_release_verification():
    workflow = COMPOSE_WORKFLOW.read_text(encoding="utf-8")

    assert (
        "FT_COMPOSE_DEMO_MODE=true ./compose/gradlew -p compose "
        ":androidApp:assembleRelease --no-configuration-cache"
    ) in workflow


def test_retired_expo_client_is_outside_the_active_ci_boundary():
    readme = LEGACY_MOBILE_README.read_text(encoding="utf-8")

    assert "只作为迁移参考" in readme
    assert "不属于默认开发、构建、测试或发布工作流" in readme
    assert "Compose Multiplatform 是当前默认客户端" in readme
    assert not LEGACY_MOBILE_WORKFLOW.exists()


def test_legacy_mobile_signing_plugin_remains_bound_to_test_credentials():
    plugin = LEGACY_SIGNING_PLUGIN.read_text(encoding="utf-8")

    assert LEGACY_TEST_KEYSTORE.is_file()
    assert "finance-tracker-test.keystore" in plugin
    assert "signingConfigs.ciTest" in plugin
    assert "FT_TEST_KEYSTORE_PASSWORD" in plugin
    assert "FT_TEST_KEY_ALIAS" in plugin
    assert "FT_TEST_KEY_PASSWORD" in plugin
