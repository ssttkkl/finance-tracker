from pathlib import Path

import pytest


class FakeImporter:
    def __init__(self, converted=(), incoming=()):
        self.converted = [dict(row) for row in converted]
        self.incoming = [dict(row) for row in incoming]
        self.calls = []

    def convert(self, command, *, mapping):
        self.calls.append(("convert", command, mapping))
        return [dict(row) for row in self.converted]

    def read_converted(self, sources):
        self.calls.append(("read", tuple(sources)))
        return [dict(row) for row in self.incoming]


class FakeMappings:
    def __init__(self, value):
        self.value = value
        self.calls = []

    def get_mapping(self, name):
        self.calls.append(name)
        return self.value


class FakeImportRepository:
    def __init__(self, error=None):
        self.rows = None
        self.error = error

    def append_cashflows(self, rows):
        self.rows = [dict(row) for row in rows]
        if self.error:
            raise self.error
        return len(rows)


class FakeChangeSets:
    def __init__(self, changed=("records/cash/2026-06.csv",)):
        self.changed = tuple(changed)
        self.staged = 0
        self.commits = []
        self.resets = 0

    def stage(self):
        self.staged += 1

    def status(self):
        return self.changed

    def commit(self, message=None):
        self.commits.append(message)
        return bool(self.changed)

    def reset(self):
        self.resets += 1
        return self.changed


def test_cashflow_convert_returns_export_without_writing_or_staging():
    from ft.application.imports import CashflowImportService
    from ft.domain.imports import CashflowConvertCommand

    row = {"date": "2026-06-01", "amount": "-1", "account_name": "Cash"}
    importer = FakeImporter(converted=[row])
    repository = FakeImportRepository()
    changes = FakeChangeSets()
    mappings = FakeMappings(([], "skip"))
    service = CashflowImportService(
        importer=importer, repository=repository,
        mappings=mappings, change_sets=changes,
    )

    result = service.convert(CashflowConvertCommand("bill.csv", "alipay"))

    assert result.ok is True
    assert result.export.rows == (row,)
    assert result.count == 1
    assert repository.rows is None
    assert changes.staged == 0
    assert mappings.calls == ["cashflow"]


def test_cashflow_append_is_one_atomic_repository_call_then_stage():
    from ft.application.imports import CashflowImportService

    rows = [
        {"date": "2026-06-02", "amount": "-2", "account_name": "Cash"},
        {"date": "2026-06-01", "amount": "-1", "account_name": "Cash"},
    ]
    importer = FakeImporter(incoming=rows)
    repository = FakeImportRepository()
    changes = FakeChangeSets()
    service = CashflowImportService(
        importer=importer, repository=repository,
        mappings=FakeMappings(None), change_sets=changes,
    )

    result = service.append(("a.csv", "b.csv"))

    assert repository.rows == rows
    assert result.count == 2
    assert result.details["by_date"] == {"2026-06-01": 1, "2026-06-02": 1}
    assert changes.staged == 1


def test_cashflow_append_failure_does_not_stage():
    from ft.application.imports import CashflowImportService

    changes = FakeChangeSets()
    service = CashflowImportService(
        importer=FakeImporter(incoming=[{"date": "2026-06-01"}]),
        repository=FakeImportRepository(ValueError("bad row")),
        mappings=FakeMappings(None), change_sets=changes,
    )

    with pytest.raises(ValueError, match="bad row"):
        service.append(("bad.csv",))

    assert changes.staged == 0


class FakeVerificationRepository:
    def __init__(self):
        self.calls = []

    def rebuild(self):
        self.calls.append("rebuild")

    def verify_cashflows(self):
        self.calls.append("cash")
        return 2, ()

    def verify_investments(self):
        from ft.domain.application import TextFinding
        self.calls.append("investment")
        return (TextFinding("investment.ok", "aligned", severity="info"),)


def test_verification_fix_rebuilds_before_both_checks():
    from ft.application.verification import VerificationService

    repository = FakeVerificationRepository()
    result = VerificationService(repository).verify(fix=True)

    assert repository.calls == ["rebuild", "cash", "investment"]
    assert result.ok is True
    assert result.rebuilt is True
    assert result.cashflow_count == 2


def test_change_set_service_exposes_semantics_without_confirmation():
    from ft.application.change_sets import ChangeSetService

    repository = FakeChangeSets(("snapshot.yaml", "records/cash/2026-06.csv"))
    service = ChangeSetService(repository)

    status = service.status()
    committed = service.commit("checkpoint")
    reset = service.reset()

    assert status.clean is False
    assert status.changed_files == repository.changed
    assert committed.details["committed"] is True
    assert reset.count == 2
    assert repository.commits == ["checkpoint"]
    assert repository.resets == 1


def test_new_application_modules_import_without_home(monkeypatch):
    def fail_home():
        raise AssertionError("application import touched home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.application.change_sets
    import ft.application.imports
    import ft.application.verification

    assert ft.application.imports.CashflowImportService


def test_cli_convert_append_verify_and_changesets_use_bundle(monkeypatch, tmp_path, capsys):
    from ft import cli
    from ft.domain.application import ChangeSetStatusDTO, ExportPayload, OperationResult
    from ft.domain.verification import VerificationResultDTO

    calls = []

    class Imports:
        def convert(self, command):
            calls.append(("convert", command))
            return OperationResult(ok=True, count=1, export=ExportPayload(({"date": "x"},), fieldnames=("date",)))

        def append(self, sources):
            calls.append(("append", tuple(sources)))
            return OperationResult(ok=True, count=2, details={"by_date": {"2026-06-01": 2}})

    class Verification:
        def verify(self, *, fix=False):
            calls.append(("verify", fix))
            return VerificationResultDTO(ok=True, rebuilt=fix, cashflow_count=0)

    class Changes:
        def status(self):
            calls.append(("status",))
            return ChangeSetStatusDTO(("snapshot.yaml",), clean=False)

        def commit(self, message=None):
            calls.append(("commit", message))
            return OperationResult(ok=True, details={"committed": True})

        def reset(self):
            calls.append(("reset",))
            return OperationResult(ok=True, count=1)

    bundle = type("Bundle", (), {
        "cashflow_imports": Imports(), "verification": Verification(),
        "change_sets": Changes(),
    })()
    monkeypatch.setattr("ft.cli.build_local_services", lambda _root: bundle)
    monkeypatch.setattr("ft.cli.write_csv_export", lambda payload, output: calls.append(("write", output, payload.rows)))
    monkeypatch.setattr("builtins.input", lambda _prompt: "y")

    cli.main(["convert", "bill.csv", "-s", "alipay", "-o", str(tmp_path / "out.csv")])
    cli.main(["append", "a.csv", "b.csv"])
    cli.main(["verify", "--fix"])
    cli.main(["status"])
    cli.main(["commit", "-m", "checkpoint"])
    cli.main(["reset"])

    assert [call[0] for call in calls] == [
        "convert", "write", "append", "verify", "status", "commit", "status", "reset",
    ]
    assert "全部校验通过" in capsys.readouterr().out
