from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_WORKFLOW = ROOT / ".github" / "workflows" / "compose-ci.yml"
LEGACY_MOBILE_WORKFLOW = ROOT / ".github" / "workflows" / "mobile-ci.yml"
LEGACY_RENDER_CONFIG = ROOT / "render.yaml"
RETIRED_CLIENT_PATHS = (ROOT / "web", ROOT / "mobile", ROOT / "packages")


def test_compose_ci_uses_checked_in_wrapper_for_android_release_verification():
    workflow = COMPOSE_WORKFLOW.read_text(encoding="utf-8")

    assert (
        "FT_COMPOSE_DEMO_MODE=true ./compose/gradlew -p compose "
        ":androidApp:assembleRelease --no-configuration-cache"
    ) in workflow


def test_legacy_clients_and_shared_packages_are_removed():
    assert all(not path.exists() for path in RETIRED_CLIENT_PATHS)
    assert not LEGACY_MOBILE_WORKFLOW.exists()
    assert not LEGACY_RENDER_CONFIG.exists()
