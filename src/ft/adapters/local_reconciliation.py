"""Local pending-file compatibility adapter for reconciliation."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

from ft.adapters.local_legacy import local_ledger_globals


class LocalReconciliationRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def state(self):
        directory = self._root / "pending" / "reconcile"
        sessions = [path for path in directory.iterdir() if path.is_dir()] if directory.exists() else []
        if len(sessions) > 1:
            raise ValueError(f"检测到多个待继续的 reconcile 会话: {sessions}")
        return "awaiting_decisions" if sessions else "idle"

    def start(self, *, month=None, date_from=None, date_to=None):
        from ft.reconcile import do_reconcile

        return do_reconcile(
            month=month,
            date_from=date_from,
            date_to=date_to,
            ledger_root=self._root,
            emit_output=False,
            stage_changes=False,
        )

    def continue_with_decisions(self):
        from ft.reconcile import continue_reconcile

        output = StringIO()
        with local_ledger_globals(self._root), redirect_stdout(output):
            continue_reconcile()
        return {"message": output.getvalue().strip() or "已应用 reconcile 决策"}

    def abort(self):
        from ft.reconcile import abort_reconcile

        output = StringIO()
        with local_ledger_globals(self._root), redirect_stdout(output):
            abort_reconcile()
        return {"message": output.getvalue().strip() or "已放弃当前 pending reconcile 会话"}
