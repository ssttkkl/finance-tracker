"""Unified snapshot for all account types"""
import copy
import subprocess
import yaml
from pathlib import Path
from typing import Optional

from . import models

# ── Snapshot path (module-level so tests can patch it) ──────────────────
SNAPSHOT_PATH = models.FT_DIR / "snapshot.yaml"

DEFAULT = {
    "updated_at": "",
    "accounts": {
        "cash": {},
        "loan": {},
        "lend": {},
        "security": {},
    },
}


# ── Internal helpers ────────────────────────────────────────────────────


def _resolve_snapshot_path(path: Optional[str] = None) -> Path:
    """Return the snapshot file path."""
    if path:
        return Path(path)
    return SNAPSHOT_PATH


# ── CRUD ────────────────────────────────────────────────────────────────


def load_snapshot(path: Optional[str] = None) -> dict:
    """Load the snapshot YAML from disk.

    Returns DEFAULT if the file does not exist or is empty.
    """
    snapshot_path = _resolve_snapshot_path(path)
    if not snapshot_path.exists():
        return copy.deepcopy(DEFAULT)  # return a mutable copy
    with snapshot_path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if data is None:
        return copy.deepcopy(DEFAULT)
    return data


def save_snapshot(data: dict, path: Optional[str] = None) -> None:
    """Write the snapshot YAML to disk."""
    snapshot_path = _resolve_snapshot_path(path)
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    with snapshot_path.open("w", encoding="utf-8") as f:
        yaml.dump(data, f, allow_unicode=True, default_flow_style=False)
    git_stage(snapshot_path.parent)


# ── Git staging & commit (transactional) ────────────────────────────────


GIT_REPO = models.FT_DIR


def git_init_repo(repo_dir=None):
    """Init ~/.ft as a git repo if not already."""
    if repo_dir is None:
        repo_dir = GIT_REPO
    repo_dir = Path(repo_dir)
    git_dir = repo_dir / ".git"
    if not git_dir.exists():
        try:
            subprocess.run(["git", "init"], cwd=str(repo_dir),
                           capture_output=True, timeout=10)
            ignore = repo_dir / ".gitignore"
            if not ignore.exists():
                ignore.write_text(".git\n__pycache__/\n*.pyc\n")
            subprocess.run(["git", "add", "-A"], cwd=str(repo_dir),
                           capture_output=True, timeout=10)
            subprocess.run(["git", "commit", "--allow-empty",
                           "-m", "🎉 auto: init ft data repo"],
                           cwd=str(repo_dir), capture_output=True, timeout=10)
        except Exception:
            pass


def git_stage(repo_dir=None):
    """Stage all changes via git add -A, no commit."""
    if repo_dir is None:
        repo_dir = GIT_REPO
    repo_dir = Path(repo_dir)
    try:
        git_init_repo(repo_dir)
        subprocess.run(["git", "add", "-A"], cwd=str(repo_dir),
                       capture_output=True, timeout=10)
    except Exception:
        pass


def git_do_commit(msg: str = None, repo_dir=None):
    """Commit all staged changes. Returns True if committed."""
    if repo_dir is None:
        repo_dir = GIT_REPO
    repo_dir = Path(repo_dir)
    try:
        git_init_repo(repo_dir)
        from datetime import datetime
        commit_msg = msg if msg else f"chore: {datetime.now().strftime('%Y-%m-%d %H:%M')}"
        result = subprocess.run(
            ["git", "commit", "-m", commit_msg],
            cwd=str(repo_dir), capture_output=True, timeout=10, text=True,
        )
        return result.returncode == 0
    except Exception:
        return False


def get_balance(acct_name: str, path: Optional[str] = None) -> tuple:
    """Search for an account's balance across types cash→loan→lend.

    Returns (balance, type) if found, or (None, None) if not found.
    Security accounts are skipped (they have a different structure).
    """
    snap = load_snapshot(path)
    search_types = ("cash", "loan", "lend")
    for typ in search_types:
        accts = snap.get("accounts", {}).get(typ, {})
        if acct_name in accts:
            return accts[acct_name], typ
    return None, None


def set_balance(snap: dict, acct_name: str, typ: str, balance: float) -> None:
    """Set the balance for an account in the snapshot dict.

    Modifies the dict in place. The caller is responsible for saving.
    """
    snap.setdefault("accounts", {}).setdefault(typ, {})[acct_name] = balance


def update_balance(snap: dict, acct_name: str, delta: float) -> None:
    """Add a delta to an existing cash/loan/lend balance.

    Only works for accounts already in the snapshot.
    No-op for unknown accounts or security accounts.
    Modifies the dict in place. The caller is responsible for saving.
    """
    search_types = ("cash", "loan", "lend")
    accounts = snap.get("accounts", {})
    for typ in search_types:
        accts = accounts.get(typ, {})
        if acct_name in accts:
            accts[acct_name] += delta
            return
