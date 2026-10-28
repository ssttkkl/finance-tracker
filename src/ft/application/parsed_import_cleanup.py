"""不依赖用户访问的解析会话清扫；只记录稳定的健康状态。"""
from __future__ import annotations

import logging
from threading import Event, Lock, Thread, current_thread

from ft.application.cash_import_errors import ImportSessionStorageUnavailable


class ParsedImportCleanup:
    def __init__(self, store, *, interval_seconds=30, page_size=100):
        if (type(interval_seconds) is not int or not 1 <= interval_seconds <= 60
            or type(page_size) is not int or not 1 <= page_size <= 200):
            raise ValueError("import_session_cleanup_config_invalid")
        self._store, self._interval, self._page_size = store, interval_seconds, page_size
        self._stopping, self._lifecycle, self._sweep_lock = Event(), Lock(), Lock()
        self._worker = None
        self._starting, self._stop_count = False, 0
        self._start_finished = Event()
        self._start_finished.set()
        self._cursor = None
        self.last_result = None
        self.healthy = None

    @property
    def running(self):
        return self._worker is not None and self._worker.is_alive()

    def sweep_once(self, *, raise_on_error=True):
        with self._sweep_lock:
            try:
                result = self._store.sweep_expired(cursor=self._cursor, limit=self._page_size, should_stop=self._stopping.is_set)
            except Exception:
                self.healthy = False
                if raise_on_error:
                    raise ImportSessionStorageUnavailable from None
                logging.getLogger(__name__).warning("cash_import_cleanup_unavailable")
                return False
            self.last_result, self._cursor, self.healthy = result, result.next_cursor, True
            return True

    def start(self):
        with self._lifecycle:
            if self.running or self._starting or self._stop_count:
                return
            self._starting = True
            self._stopping, self._start_finished = Event(), Event()
            stopping, finished = self._stopping, self._start_finished
        try:
            self.sweep_once()
            with self._lifecycle:
                if not stopping.is_set():
                    self._worker = Thread(target=self._run, name="cash-import-cleanup", daemon=True)
                    self._worker.start()
        finally:
            with self._lifecycle:
                self._starting = False
                finished.set()

    def stop(self):
        with self._lifecycle:
            self._stop_count += 1
            self._stopping.set()
            worker, finished = self._worker, self._start_finished
        try:
            finished.wait()
            if worker is not None and worker is not current_thread():
                worker.join()
        finally:
            with self._lifecycle:
                self._stop_count -= 1

    def _run(self):
        wait_seconds = 0 if self._cursor is not None else self._interval
        while not self._stopping.wait(wait_seconds):
            success = self.sweep_once(raise_on_error=False)
            wait_seconds = 0 if success and self._cursor is not None else self._interval
