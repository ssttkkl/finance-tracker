import json
import shutil
from datetime import datetime
from pathlib import Path

from . import models


def _kind_dir(kind: str) -> Path:
    if kind == "convert":
        return models.PENDING_DIR / "convert"
    if kind == "reconcile":
        return models.PENDING_DIR / "reconcile"
    raise ValueError(f"unknown pending kind: {kind}")


def _ensure_kind(kind: str) -> Path:
    path = _kind_dir(kind)
    path.mkdir(parents=True, exist_ok=True)
    return path


def find_pending_sessions(kind: str) -> list[Path]:
    kind_dir = _ensure_kind(kind)
    return sorted([p for p in kind_dir.iterdir() if p.is_dir()])


def format_pending_guidance(kind: str, session_dir: Path, *, existing_session: bool = False) -> str:
    ai_working_csv = session_dir / "ai_working.csv"
    continue_cmd = f"ft {kind} --continue-with-decisions {ai_working_csv}"
    abort_cmd = f"ft {kind} --abort"
    if existing_session:
        header = f"❌ 当前已有未完成的 {kind} 会话: {session_dir}"
    else:
        header = f"🕒 已进入待决策状态: {session_dir}"
    return "\n".join([
        header,
        f"请处理: {ai_working_csv}",
        "请按 SKILL.md 中的 pending / ai_working.csv 审查流程检查并编辑该文件。",
        "必须审查整份 ai_working.csv，不要只看局部候选行。",
        "如果数据量大，按交易日期切成三个月一批；每批只交给一个 subagent，并要求 subagent 通过推理输出标记结果，禁止用脚本批量过滤/批量判定。",
        "详细提示词、允许修改列、审查步骤见 SKILL.md。",
        f"继续执行: {continue_cmd}",
        f"放弃本次会话: {abort_cmd}",
    ])


def require_no_pending_session(kind: str):
    sessions = find_pending_sessions(kind)
    if sessions:
        raise ValueError(format_pending_guidance(kind, sessions[0], existing_session=True))


def require_single_pending_session(kind: str) -> Path:
    sessions = find_pending_sessions(kind)
    if not sessions:
        raise ValueError(f"❌ 当前没有待继续的 {kind} 会话")
    if len(sessions) > 1:
        raise ValueError(f"❌ 检测到多个待继续的 {kind} 会话，请手动清理: {sessions}")
    return sessions[0]


def load_pending_session(kind: str) -> dict | None:
    sessions = find_pending_sessions(kind)
    if not sessions:
        return None
    if len(sessions) > 1:
        raise ValueError(f"❌ 检测到多个待继续的 {kind} 会话，请手动清理: {sessions}")
    session_dir = sessions[0]
    return {
        "session_dir": session_dir,
        "manifest": load_manifest(session_dir),
        "status": json.loads((session_dir / "status.json").read_text(encoding="utf-8")),
    }


def create_pending_session(kind: str, manifest: dict) -> Path:
    require_no_pending_session(kind)
    kind_dir = _ensure_kind(kind)
    ts = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    session_id = f"{kind}_{ts}"
    session_dir = kind_dir / session_id
    session_dir.mkdir(parents=True, exist_ok=False)
    manifest = {**manifest, "session_id": session_id, "kind": kind, "created_at": ts}
    write_json(session_dir / "manifest.json", manifest)
    write_json(session_dir / "status.json", {"session_id": session_id, "status": "waiting_for_decisions"})
    return session_dir


def load_manifest(session_dir: Path) -> dict:
    return json.loads((session_dir / "manifest.json").read_text(encoding="utf-8"))


def write_status(session_dir: Path, status: str):
    manifest = load_manifest(session_dir)
    write_json(session_dir / "status.json", {"session_id": manifest["session_id"], "status": status})


def clear_pending_session(kind: str):
    session_dir = require_single_pending_session(kind)
    shutil.rmtree(session_dir)


def write_json(path: Path, payload: dict | list):
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
