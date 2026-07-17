from pathlib import Path


class FakeReconciliationRepository:
    def __init__(self, state="idle", start_state="completed"):
        self.current = state
        self.start_state = start_state
        self.calls = []

    def state(self):
        return self.current

    def start(self, *, month=None, date_from=None, date_to=None):
        self.calls.append(("start", month, date_from, date_to))
        self.current = "awaiting_decisions" if self.start_state == "awaiting_decisions" else "idle"
        return {
            "message": "started", "removed": 1, "transfer_matches": 2,
            "single_leg_marks": 3, "audit_path": None,
        }

    def continue_with_decisions(self):
        self.calls.append(("continue",))
        self.current = "idle"
        return {"message": "continued"}

    def abort(self):
        self.calls.append(("abort",))
        self.current = "idle"
        return {"message": "aborted"}


class FakeChanges:
    def __init__(self):
        self.staged = 0

    def stage(self):
        self.staged += 1


def test_start_returns_awaiting_state_and_stages_changes():
    from ft.application.reconcile import ReconcileService
    from ft.domain.reconciliation import ReconciliationState

    repository = FakeReconciliationRepository(start_state="awaiting_decisions")
    changes = FakeChanges()
    result = ReconcileService(repository, changes).start(month="2026-06")

    assert result.ok is True
    assert result.state is ReconciliationState.AWAITING_DECISIONS
    assert result.removed == 1
    assert repository.calls == [("start", "2026-06", None, None)]
    assert changes.staged == 1


def test_start_returns_completed_when_repository_has_no_pending_session():
    from ft.application.reconcile import ReconcileService
    from ft.domain.reconciliation import ReconciliationState

    result = ReconcileService(
        FakeReconciliationRepository(start_state="completed"), FakeChanges()
    ).start(date_from="2026-06-01", date_to="2026-06-30")

    assert result.state is ReconciliationState.COMPLETED


def test_invalid_transitions_return_structured_error_without_repository_call():
    from ft.application.reconcile import ReconcileService

    idle = FakeReconciliationRepository(state="idle")
    service = ReconcileService(idle, FakeChanges())
    continued = service.continue_with_decisions()
    aborted = service.abort()

    assert continued.ok is False
    assert continued.error.code == "reconciliation.invalid_state"
    assert aborted.ok is False
    assert idle.calls == []


def test_continue_and_abort_are_allowed_only_while_awaiting():
    from ft.application.reconcile import ReconcileService
    from ft.domain.reconciliation import ReconciliationState

    continuing = FakeReconciliationRepository(state="awaiting_decisions")
    changes = FakeChanges()
    continued = ReconcileService(continuing, changes).continue_with_decisions()

    aborting = FakeReconciliationRepository(state="awaiting_decisions")
    aborted = ReconcileService(aborting, FakeChanges()).abort()

    assert continued.state is ReconciliationState.COMPLETED
    assert continuing.calls == [("continue",)]
    assert changes.staged == 1
    assert aborted.state is ReconciliationState.ABORTED
    assert aborting.calls == [("abort",)]


def test_reconciliation_application_import_does_not_touch_home(monkeypatch):
    def fail_home():
        raise AssertionError("reconciliation application import touched home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.application.reconcile
    import ft.domain.reconciliation

    assert ft.application.reconcile.ReconcileService


def test_cli_all_reconcile_leaves_enter_service_bundle(monkeypatch, capsys):
    from ft import cli
    from ft.domain.reconciliation import ReconcileResultDTO, ReconciliationState

    calls = []

    class Reconciliation:
        def start(self, **kwargs):
            calls.append(("start", kwargs))
            return ReconcileResultDTO(True, ReconciliationState.COMPLETED, "started")

        def continue_with_decisions(self):
            calls.append(("continue",))
            return ReconcileResultDTO(True, ReconciliationState.COMPLETED, "continued")

        def abort(self):
            calls.append(("abort",))
            return ReconcileResultDTO(True, ReconciliationState.ABORTED, "aborted")

    bundle = type("Bundle", (), {"reconciliation": Reconciliation()})()
    monkeypatch.setattr("ft.cli.build_local_services", lambda _root: bundle)

    cli.main(["reconcile", "--month", "2026-06"])
    cli.main(["reconcile", "--continue-with-decisions"])
    cli.main(["reconcile", "--abort"])

    assert calls == [
        ("start", {"month": "2026-06", "date_from": None, "date_to": None}),
        ("continue",),
        ("abort",),
    ]
    assert "started" in capsys.readouterr().out
