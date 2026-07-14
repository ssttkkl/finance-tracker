import json
import shutil
from datetime import datetime
from pathlib import Path

from . import models


def _reconcile_pending_dir() -> Path:
    return models.PENDING_DIR / "reconcile"


def _ensure_reconcile_pending_dir() -> Path:
    path = _reconcile_pending_dir()
    path.mkdir(parents=True, exist_ok=True)
    return path


def find_reconcile_pending_sessions() -> list[Path]:
    pending_dir = _ensure_reconcile_pending_dir()
    return sorted([p for p in pending_dir.iterdir() if p.is_dir()])


def format_reconcile_pending_guidance(session_dir: Path, *, existing_session: bool = False) -> str:
    ai_working_csv = session_dir / "ai_working.csv"
    edited_csv = session_dir / "edited.csv"
    continue_cmd = "ft reconcile --continue-with-decisions"
    abort_cmd = "ft reconcile --abort"
    if existing_session:
        header = f"❌ 当前已有未完成的 reconcile 会话: {session_dir}"
    else:
        header = f"🕒 已进入待决策状态: {session_dir}"
    return "\n".join([
        header,
        f"请处理: {ai_working_csv}",
        f"审查完成后保存为: {edited_csv}",
        "请按 SKILL.md 中的 pending / ai_working.csv 审查流程检查并编辑该文件。",
        "必须审查整份 ai_working.csv，不要只看局部候选行。",
        "如果数据量大，按交易日期切成三个月一批；每批只交给一个 subagent，并要求 subagent 通过推理输出标记结果，禁止用脚本批量过滤/批量判定。",
        "详细提示词、允许修改列、审查步骤见 SKILL.md。",
        f"继续执行: {continue_cmd}",
        f"放弃本次会话: {abort_cmd}",
    ])


def require_no_reconcile_pending_session():
    sessions = find_reconcile_pending_sessions()
    if sessions:
        raise ValueError(format_reconcile_pending_guidance(sessions[0], existing_session=True))


def require_single_reconcile_pending_session() -> Path:
    sessions = find_reconcile_pending_sessions()
    if not sessions:
        raise ValueError("❌ 当前没有待继续的 reconcile 会话")
    if len(sessions) > 1:
        raise ValueError(f"❌ 检测到多个待继续的 reconcile 会话，请手动清理: {sessions}")
    return sessions[0]


def load_reconcile_pending_session() -> dict | None:
    sessions = find_reconcile_pending_sessions()
    if not sessions:
        return None
    if len(sessions) > 1:
        raise ValueError(f"❌ 检测到多个待继续的 reconcile 会话，请手动清理: {sessions}")
    session_dir = sessions[0]
    return {
        "session_dir": session_dir,
        "manifest": load_manifest(session_dir),
        "status": json.loads((session_dir / "status.json").read_text(encoding="utf-8")),
    }


def create_reconcile_pending_session(manifest: dict) -> Path:
    require_no_reconcile_pending_session()
    pending_dir = _ensure_reconcile_pending_dir()
    ts = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    session_id = f"reconcile_{ts}"
    session_dir = pending_dir / session_id
    session_dir.mkdir(parents=True, exist_ok=False)
    manifest = {**manifest, "session_id": session_id, "kind": "reconcile", "created_at": ts}
    write_json(session_dir / "manifest.json", manifest)
    write_json(session_dir / "status.json", {"session_id": session_id, "status": "waiting_for_decisions"})
    return session_dir


def load_manifest(session_dir: Path) -> dict:
    return json.loads((session_dir / "manifest.json").read_text(encoding="utf-8"))


def write_status(session_dir: Path, status: str):
    manifest = load_manifest(session_dir)
    write_json(session_dir / "status.json", {"session_id": manifest["session_id"], "status": status})


def clear_reconcile_pending_session():
    session_dir = require_single_reconcile_pending_session()
    shutil.rmtree(session_dir)


def write_json(path: Path, payload: dict | list):
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
