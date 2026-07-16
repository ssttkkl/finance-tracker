"""Unified snapshot for all account types"""
import copy
import subprocess
import tempfile
import yaml
from pathlib import Path
from typing import Optional

from .schema import DEFAULT_SNAPSHOT

# ── Snapshot path (module-level so tests can patch it) ──────────────────
SNAPSHOT_PATH = None

DEFAULT = DEFAULT_SNAPSHOT


# ── Internal helpers ────────────────────────────────────────────────────


def _resolve_snapshot_path(path: Optional[str] = None) -> Path:
    """Return the snapshot file path."""
    if path:
        return Path(path)
    if SNAPSHOT_PATH is not None:
        return Path(SNAPSHOT_PATH)
    from . import models
    return Path(models.FT_DIR) / "snapshot.yaml"


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


def save_snapshot(data: dict, path: Optional[str] = None, stage_changes: bool = True) -> None:
    """Write the snapshot YAML to disk."""
    snapshot_path = _resolve_snapshot_path(path)
    snapshot_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=snapshot_path.parent, delete=False) as f:
            tmp_path = Path(f.name)
            yaml.dump(data, f, allow_unicode=True, default_flow_style=False)
        tmp_path.replace(snapshot_path)
    finally:
        if tmp_path is not None and tmp_path.exists():
            tmp_path.unlink(missing_ok=True)
    if stage_changes:
        git_stage(snapshot_path.parent)


# ── Git staging & commit (transactional) ────────────────────────────────


GIT_REPO = None


def _resolve_git_repo(repo_dir=None) -> Path:
    if repo_dir is not None:
        return Path(repo_dir)
    if GIT_REPO is not None:
        return Path(GIT_REPO)
    from . import models
    return Path(models.FT_DIR)


def git_init_repo(repo_dir=None):
    """Init ~/.ft as a git repo if not already."""
    repo_dir = _resolve_git_repo(repo_dir)
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
    """Stage all changes via git add -A, no commit. Prints reminder to commit."""
    repo_dir = _resolve_git_repo(repo_dir)
    try:
        git_init_repo(repo_dir)
        subprocess.run(["git", "add", "-A"], cwd=str(repo_dir),
                       capture_output=True, timeout=10)
        print("💡 改动已暂存，执行 ft commit 提交")
    except Exception:
        pass


def git_do_commit(msg: str = None, repo_dir=None):
    """Commit all staged changes. Returns True if committed."""
    repo_dir = _resolve_git_repo(repo_dir)
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


def _normalize_balance_bucket(bucket, currency: Optional[str] = None):
    """Return a currency-aware balance bucket from legacy or nested data."""
    if isinstance(bucket, dict):
        if currency is None:
            return bucket
        return bucket.get(currency)
    return bucket


def get_balance(
    acct_name: str,
    currency: Optional[str] = None,
    path: Optional[str] = None,
) -> tuple:
    """Search for an account's balance across types cash→loan→lend.

    Returns (balance, type) if found, or (None, None) if not found.
    Security accounts are skipped (they have a different structure).
    """
    snap = load_snapshot(path)
    search_types = ("cash", "loan", "lend")
    for typ in search_types:
        accts = snap.get("accounts", {}).get(typ, {})
        if acct_name not in accts:
            continue
        bucket = accts[acct_name]
        if isinstance(bucket, dict):
            if currency is None:
                return bucket, typ
            if currency in bucket:
                return bucket[currency], typ
            continue
        return bucket, typ
    return None, None


def set_balance(
    snap: dict,
    acct_name: str,
    typ: str,
    currency_or_balance,
    balance: Optional[float] = None,
) -> None:
    """Set the balance for an account in the snapshot dict.

    Supports both legacy flat writes and the new nested per-currency shape.
    """
    accounts = snap.setdefault("accounts", {})
    bucket = accounts.setdefault(typ, {})
    if balance is None:
        bucket[acct_name] = currency_or_balance
        return
    acct_bucket = bucket.setdefault(acct_name, {})
    if not isinstance(acct_bucket, dict):
        acct_bucket = {"CNY": acct_bucket}
        bucket[acct_name] = acct_bucket
    acct_bucket[currency_or_balance] = balance


def update_balance(
    snap: dict,
    acct_name: str,
    currency_or_delta,
    delta: Optional[float] = None,
) -> None:
    """Add a delta to an existing cash/loan/lend balance.

    Supports both legacy flat updates and currency-aware nested balances.
    """
    search_types = ("cash", "loan", "lend")
    accounts = snap.get("accounts", {})
    for typ in search_types:
        accts = accounts.get(typ, {})
        if acct_name not in accts:
            continue
        bucket = accts[acct_name]
        if delta is None:
            if isinstance(bucket, dict):
                for cur, bal in list(bucket.items()):
                    bucket[cur] = bal + currency_or_delta
            else:
                accts[acct_name] = bucket + currency_or_delta
            return
        if isinstance(bucket, dict):
            bucket[currency_or_delta] = bucket.get(currency_or_delta, 0.0) + delta
        else:
            accts[acct_name] = bucket + delta
        return


def rebuild_snapshot_from_records(records_dir=None, snapshot_path=None, stage_changes: bool = True,
                                  repair_security_snapshot: bool = True):
    """Rebuild cash/loan/lend balances from CSV records."""
    if records_dir is None:
        from . import models
        records_dir = models.RECORDS_DIR

    from collections import defaultdict
    import csv
    import re
    if repair_security_snapshot:
        from .stock import repair_security
        records_root = Path(records_dir).parent
        repair_security(
            records_dir,
            accounts_path=records_root / "accounts.yaml",
            snapshot_path=snapshot_path,
            stage_changes=False,
            emit_output=False,
        )

    snap = load_snapshot(snapshot_path)
    for typ in ("cash", "loan", "lend"):
        typedir = Path(records_dir) / typ
        if not typedir.exists():
            continue
        acct_records = defaultdict(list)
        for csv_file in sorted(typedir.glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    acct = row.get("account_name", "").strip()
                    currency = row.get("currency", "").strip() or "CNY"
                    if acct:
                        acct_records[(acct, currency)].append(row)

        for (acct_name, currency), records in acct_records.items():
            records.sort(key=lambda r: r["date"])
            last_ci = -1
            for i, r in enumerate(records):
                if r.get("category") == "checkin":
                    last_ci = i
            if last_ci >= 0:
                desc = records[last_ci].get("description", "")
                m = re.search(r"[\d,]+\.?\d*", desc.replace(",", ""))
                bal = float(m.group()) if m else 0.0
                start = last_ci + 1
            else:
                bal = 0.0
                start = 0
            for r in records[start:]:
                cat = r.get("category", "")
                if cat in ("checkin", "transfer", "transfer_in", "transfer_out"):
                    continue
                try:
                    bal += float(r["amount"])
                except (ValueError, KeyError):
                    pass
            set_balance(snap, acct_name, typ, currency, round(bal, 2))

    snap["updated_at"] = "rebuilt"
    if stage_changes:
        save_snapshot(snap, snapshot_path)
    else:
        snapshot_file = _resolve_snapshot_path(snapshot_path)
        snapshot_file.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=snapshot_file.parent, delete=False) as f:
            tmp_path = Path(f.name)
            yaml.dump(snap, f, allow_unicode=True, default_flow_style=False)
        tmp_path.replace(snapshot_file)
    return snap
