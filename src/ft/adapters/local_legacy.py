"""Scoped compatibility for legacy modules that still expose global paths."""
from contextlib import contextmanager
from pathlib import Path


@contextmanager
def local_ledger_globals(ledger_root):
    from ft import models
    import ft.snapshot as snapshot

    root = Path(ledger_root)
    old = (
        models.FT_DIR,
        models.RECORDS_DIR,
        models.ACCOUNTS_PATH,
        models.PENDING_DIR,
        snapshot.SNAPSHOT_PATH,
    )
    models.FT_DIR = root
    models.RECORDS_DIR = root / "records"
    models.ACCOUNTS_PATH = root / "accounts.yaml"
    models.PENDING_DIR = root / "pending"
    snapshot.SNAPSHOT_PATH = root / "snapshot.yaml"
    try:
        yield
    finally:
        (
            models.FT_DIR,
            models.RECORDS_DIR,
            models.ACCOUNTS_PATH,
            models.PENDING_DIR,
            snapshot.SNAPSHOT_PATH,
        ) = old
