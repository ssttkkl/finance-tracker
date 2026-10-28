"""Temporary synthetic-workload diagnostics; never an acceptance check."""
import collections
import cProfile
from functools import wraps
import json
from pathlib import Path
import pstats
import sys
import time

import pytest
from sqlalchemy import event
from sqlalchemy.engine import Engine

from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_mutation_commit import CashMutationCommandService


def _plan_shape(value):
    if isinstance(value, list):
        return [_plan_shape(item) for item in value]
    if isinstance(value, dict):
        return {key: _plan_shape(item) for key, item in value.items() if key in {
            "Plan", "Plans", "Node Type", "Join Type", "Relation Name", "Index Name",
            "Actual Startup Time", "Actual Total Time", "Actual Rows", "Actual Loops",
            "Rows Removed by Filter", "Rows Removed by Join Filter", "Heap Fetches",
            "Shared Hit Blocks", "Shared Read Blocks", "Planning Time", "Execution Time"}}
    return value


def main(output):
    output.mkdir(parents=True, exist_ok=True)
    profiles, queries, pending = {}, collections.defaultdict(list), {}
    completed = collections.Counter()
    examples, explained = {}, set()
    current, active = "", None

    class TestContext:
        def pytest_runtest_setup(self, item):
            nonlocal current
            current = item.nodeid

    def wrap(original, operation):
        @wraps(original)
        def measured(*args, **kwargs):
            nonlocal active
            key = current + " " + operation
            profiler = profiles.setdefault(key, cProfile.Profile())
            active = key
            try:
                result = profiler.runcall(original, *args, **kwargs)
            finally:
                active = None
            completed[key] += 1
            if operation == "commit" and key not in explained:
                explained.add(key)
                totals = collections.Counter()
                for elapsed, statement in queries[key]:
                    totals[statement] += elapsed
                with (output / "postgres-plans.txt").open("a") as stream:
                    for statement, _ in totals.most_common(12):
                        example = examples.get((key, statement))
                        if example is None:
                            continue
                        engine, parameters = example
                        try:
                            with engine.connect() as connection:
                                plan = connection.exec_driver_sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + statement, parameters).scalar_one()
                        except Exception:
                            raise RuntimeError("profile_explain_failed") from None
                        stream.write(statement + "\n" + json.dumps(_plan_shape(plan), indent=2) + "\n\n")
            return result
        return measured

    def before(conn, cursor, statement, parameters, context, executemany):
        if active:
            pending[id(context)] = active, time.perf_counter_ns(), statement
            if conn.dialect.name == "postgresql" and "FOR UPDATE" not in statement and any(
                    text in statement for text in ("FROM cash_projection", "FROM cash_entry", "FROM cash_transaction", "FROM transaction_relations")):
                if statement.startswith(("SELECT", "WITH")):
                    examples.setdefault((active, statement), (conn.engine, parameters))

    def after(conn, cursor, statement, parameters, context, executemany):
        entry = pending.pop(id(context), None)
        if entry:
            key, started, sql = entry
            queries[key].append((time.perf_counter_ns() - started, sql))

    preview, commit = CashImportCommandService.preview, CashMutationCommandService.commit
    CashImportCommandService.preview = wrap(preview, "preview")
    CashMutationCommandService.commit = wrap(commit, "commit")
    event.listen(Engine, "before_cursor_execute", before)
    event.listen(Engine, "after_cursor_execute", after)
    try:
        result = pytest.main(["-q", "-s", "--tb=no", "-rN",
            "tests/test_cash_current_relation_performance.py",
            "tests/test_cash_import_performance.py::test_fixed_10k_current_import_preview_keeps_original_batch_budget"],
            plugins=[TestContext()])
    finally:
        CashImportCommandService.preview, CashMutationCommandService.commit = preview, commit
        event.remove(Engine, "before_cursor_execute", before)
        event.remove(Engine, "after_cursor_execute", after)
        for index, (key, profiler) in enumerate(sorted(profiles.items())):
            profiler.dump_stats(str(output / f"profile-{index}.prof"))
            with (output / f"profile-{index}.txt").open("w") as stream:
                stream.write(key + "\n")
                stats = pstats.Stats(profiler, stream=stream)
                stats.sort_stats("cumtime").print_stats(80)
                stats.sort_stats("tottime").print_stats(80)
            totals, counts = collections.Counter(), collections.Counter()
            for elapsed, statement in queries[key]:
                totals[statement] += elapsed
                counts[statement] += 1
            with (output / f"sql-{index}.txt").open("w") as stream:
                stream.write(key + "\n")
                for statement, elapsed in totals.most_common():
                    stream.write(f"{counts[statement]} calls {elapsed / 1e6:.3f} ms\n{statement}\n\n")
    (output / "diagnostic-result.txt").write_text(
        f"Instrumented pytest exit: {result}\nProfiling timings are not acceptance evidence.\n"
        + json.dumps(completed, indent=2) + "\n")
    for path in sorted(output.glob("*.txt")):
        print(path.name, flush=True)
        print(path.read_text(), flush=True)
    from test_cash_projection_performance import EDIT_SAMPLES, EDIT_WARMUPS
    expected = {}
    for backend in ("sqlite", "postgresql"):
        expected[f"tests/test_cash_current_relation_performance.py::test_current_pair_selection_and_commit_in_10k_ledger_meet_relation_budget[{backend}] commit"] = EDIT_SAMPLES + EDIT_WARMUPS
        expected[f"tests/test_cash_import_performance.py::test_fixed_10k_current_import_preview_keeps_original_batch_budget[{backend}] preview"] = 1
    if completed != expected or result not in (pytest.ExitCode.OK, pytest.ExitCode.TESTS_FAILED):
        raise SystemExit(result or 1)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
