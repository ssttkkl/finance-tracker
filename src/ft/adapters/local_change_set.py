"""Git-backed local change-set adapter."""
from contextlib import redirect_stdout
from datetime import datetime
from io import StringIO
from pathlib import Path
import subprocess


class LocalGitChangeSetRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def stage(self):
        from ft.snapshot import git_stage

        with redirect_stdout(StringIO()):
            git_stage(self._root)

    def status(self):
        if not (self._root / ".git").exists():
            return ()
        result = subprocess.run(
            ["git", "status", "--short"], cwd=self._root,
            capture_output=True, timeout=10, text=True,
        )
        return tuple(line for line in result.stdout.splitlines() if line)

    def commit(self, message=None):
        self.stage()
        commit_message = message or f"chore: {datetime.now().strftime('%Y-%m-%d %H:%M')}"
        result = subprocess.run(
            ["git", "commit", "-m", commit_message], cwd=self._root,
            capture_output=True, timeout=10, text=True,
        )
        return result.returncode == 0

    def reset(self):
        changed = self.status()
        if changed:
            subprocess.run(
                ["git", "reset", "--hard", "HEAD"], cwd=self._root,
                capture_output=True, timeout=10,
            )
        return changed
