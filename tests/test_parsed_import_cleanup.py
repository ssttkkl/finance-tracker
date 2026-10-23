from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest


class Store:
    def __init__(self):
        self.calls = []
        self.failed = False
        self.visited = Event()

    def sweep_expired(self, **options):
        from ft.application.parsed_import_staging import ParsedImportSweep
        self.calls.append(options)
        if self.failed:
            raise RuntimeError("secret provider details")
        if options["cursor"] is not None:
            self.visited.set()
        return ParsedImportSweep(1, 1, "next-page" if options["cursor"] is None else None)


def test_cleanup_worker_visits_next_pages_without_user_requests_and_stops():
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    store = Store()
    worker = ParsedImportCleanup(store, interval_seconds=60, page_size=2)
    worker.start()
    try:
        assert store.visited.wait(2)
        assert worker.last_result is not None
    finally:
        worker.stop()
    assert not worker.running
    assert all(call["limit"] == 2 for call in store.calls)


def test_first_page_failure_prevents_start_without_leaking_provider_details():
    from ft.application.cash_import_errors import ImportSessionStorageUnavailable
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    store = Store(); store.failed = True
    worker = ParsedImportCleanup(store)
    with pytest.raises(ImportSessionStorageUnavailable) as error:
        worker.start()
    assert not worker.running and worker.healthy is False
    assert "secret" not in str(error.value)


def test_stop_during_startup_reaches_the_sweep_and_prevents_worker_creation():
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    from ft.application.parsed_import_staging import ParsedImportSweep
    entered, stopped = Event(), Event()
    class SlowStartupStore:
        def sweep_expired(self, **options):
            entered.set()
            for _ in range(200):
                if options["should_stop"]():
                    stopped.set()
                    return ParsedImportSweep(0, 0, None)
                Event().wait(.01)
            raise RuntimeError("stop signal never reached initial sweep")
    worker = ParsedImportCleanup(SlowStartupStore())
    with ThreadPoolExecutor(max_workers=2) as executor:
        starting = executor.submit(worker.start)
        assert entered.wait(2)
        stopping = executor.submit(worker.stop)
        assert stopped.wait(1), "stop must signal an in-flight initial sweep"
        stopping.result(timeout=2); starting.result(timeout=2)
    assert not worker.running


def test_runtime_failure_marks_unhealthy_retains_progress_and_can_recover(caplog):
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    worker = ParsedImportCleanup(Store())
    worker.sweep_once()
    assert worker.healthy and worker.last_result.next_cursor == "next-page"
    worker._store.failed = True
    assert worker.sweep_once(raise_on_error=False) is False
    assert not worker.healthy and worker.last_result.next_cursor == "next-page"
    assert "secret" not in caplog.text and "cash_import_cleanup_unavailable" in caplog.text
    worker._store.failed = False
    assert worker.sweep_once() is True and worker.healthy
    assert worker.last_result.next_cursor is None


@pytest.mark.parametrize("options", [{"interval_seconds": 0}, {"interval_seconds": 61}, {"page_size": 201}, {"page_size": True}])
def test_cleanup_configuration_is_bounded(options):
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    with pytest.raises(ValueError, match="import_session_cleanup_config_invalid"):
        ParsedImportCleanup(Store(), **options)


def test_config_builds_parsed_only_store_and_cleanup_with_explicit_backend():
    from ft.config import build_parsed_import_store
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    store, cleanup = build_parsed_import_store(environ={"FT_IMPORT_STAGING_BACKEND": "memory", "FT_IMPORT_STAGING_ALLOW_MEMORY": "1"})
    assert isinstance(store, InMemoryParsedImportStore) and isinstance(cleanup, ParsedImportCleanup)


@pytest.mark.parametrize("name,value", [("FT_IMPORT_STAGING_SWEEP_SECONDS", "nan"), ("FT_IMPORT_STAGING_SWEEP_SECONDS", "61"), ("FT_IMPORT_STAGING_SWEEP_PAGE_SIZE", "0")])
def test_config_rejects_invalid_cleanup_settings(name, value):
    from ft.config import build_parsed_import_store, StorageConfigurationError
    with pytest.raises(StorageConfigurationError):
        build_parsed_import_store(environ={"FT_IMPORT_STAGING_BACKEND": "memory", "FT_IMPORT_STAGING_ALLOW_MEMORY": "1", name: value})


def test_runtime_lifespan_starts_and_stops_required_cleanup(cash_web_runtime, monkeypatch):
    from fastapi.testclient import TestClient
    from ft.web.app import create_runtime_app
    monkeypatch.setenv("FT_DATABASE_URL", str(cash_web_runtime.sessions.kw["bind"].url))
    monkeypatch.setenv("FT_IMPORT_STAGING_BACKEND", "memory")
    monkeypatch.setenv("FT_IMPORT_STAGING_ALLOW_MEMORY", "1")
    app = create_runtime_app()
    with TestClient(app):
        assert app.state.parsed_import_cleanup.running
        assert app.state.parsed_import_cleanup.healthy
    assert not app.state.parsed_import_cleanup.running


def test_runtime_startup_refuses_unavailable_cleanup(cash_web_runtime, monkeypatch):
    from fastapi.testclient import TestClient
    from ft.application.cash_import_errors import ImportSessionStorageUnavailable
    from ft.application.parsed_import_cleanup import ParsedImportCleanup
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    import ft.web.app as module
    monkeypatch.setenv("FT_DATABASE_URL", str(cash_web_runtime.sessions.kw["bind"].url))
    monkeypatch.setenv("FT_IMPORT_STAGING_BACKEND", "memory")
    monkeypatch.setenv("FT_IMPORT_STAGING_ALLOW_MEMORY", "1")
    failed = Store(); failed.failed = True
    cleanup = ParsedImportCleanup(failed)
    monkeypatch.setattr(module, "build_parsed_import_store", lambda: (InMemoryParsedImportStore(), cleanup))
    app = module.create_runtime_app()
    with pytest.raises(ImportSessionStorageUnavailable):
        with TestClient(app):
            pytest.fail("startup must refuse an unavailable cleanup dependency")
    assert not cleanup.running
