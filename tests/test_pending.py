import json
from pathlib import Path

import pytest

from ft import models


@pytest.fixture
def tmp_pending_env(monkeypatch, tmp_path):
    monkeypatch.setattr(models, "FT_DIR", tmp_path)
    monkeypatch.setattr(models, "PENDING_DIR", tmp_path / "pending")
    return tmp_path


def test_load_pending_session_returns_manifest_and_status(tmp_pending_env):
    from ft.pending import create_reconcile_pending_session, load_reconcile_pending_session

    session_dir = create_reconcile_pending_session({"scope_from": "2026-06-01"})
    status_path = session_dir / "status.json"
    status_path.write_text(
        json.dumps({"session_id": session_dir.name, "status": "continued"}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    pending = load_reconcile_pending_session()

    assert pending is not None
    assert pending["session_dir"] == session_dir
    assert pending["manifest"]["session_id"] == session_dir.name
    assert pending["manifest"]["scope_from"] == "2026-06-01"
    assert pending["status"] == {"session_id": session_dir.name, "status": "continued"}


def test_load_pending_session_returns_none_when_absent(tmp_pending_env):
    from ft.pending import load_reconcile_pending_session

    assert load_reconcile_pending_session() is None


def test_write_status_updates_status_file(tmp_pending_env):
    from ft.pending import create_reconcile_pending_session, write_status

    session_dir = create_reconcile_pending_session({"scope_from": "2026-06-01"})
    write_status(session_dir, "aborted")

    status = json.loads((session_dir / "status.json").read_text(encoding="utf-8"))
    assert status == {"session_id": session_dir.name, "status": "aborted"}


def test_create_reconcile_pending_session_rejects_second_session(tmp_pending_env):
    from ft.pending import create_reconcile_pending_session

    create_reconcile_pending_session({"scope_from": "2026-06-01"})

    with pytest.raises(ValueError) as exc:
        create_reconcile_pending_session({"scope_from": "2026-07-01"})

    message = str(exc.value)
    assert "已有未完成的 reconcile 会话" in message
    assert "ai_working.csv" in message
    assert "edited.csv" in message
    assert "SKILL.md" in message
    assert "整份 ai_working.csv" in message
    assert "三个月一批" in message
    assert "--continue-with-decisions" in message
    assert "--abort" in message
