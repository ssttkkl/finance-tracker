from datetime import datetime, timezone
from dataclasses import fields
from decimal import Decimal
import math
import random

import pytest

from ft.application import parsed_import_staging as module
from ft.application.parsed_import_staging import (ParsedImportDataError, ParsedImportSession, ParsedImportFile,
    ParsedCashDocument, ParsedCashRow, SourceCoverage)


def _reference_budget(value, budget, *, depth=0):
    if depth > 64:
        raise ParsedImportDataError("import_session_json_invalid")
    if isinstance(value, dict):
        budget[0] -= 2 + 2 * len(value)
        for key, item in value.items():
            if not isinstance(key, str):
                raise ParsedImportDataError("import_session_json_invalid")
            try:
                budget[0] -= len(key) if key.isascii() else len(key.encode("utf-8"))
            except UnicodeError:
                raise ParsedImportDataError("import_session_json_invalid") from None
            if budget[0] < 0:
                raise ParsedImportDataError("import_session_draft_too_large")
            if depth == 64:
                raise ParsedImportDataError("import_session_json_invalid")
            if isinstance(item, str):
                try:
                    budget[0] -= len(item) if item.isascii() else len(item.encode("utf-8"))
                except UnicodeError:
                    raise ParsedImportDataError("import_session_json_invalid") from None
            elif item is None or type(item) in (bool, int):
                budget[0] -= len(str(item))
            elif type(item) in (dict, list, tuple) and not item:
                budget[0] -= 2
            else:
                _reference_budget(item, budget, depth=depth + 1)
            if budget[0] < 0:
                raise ParsedImportDataError("import_session_draft_too_large")
    elif isinstance(value, (list, tuple)):
        budget[0] -= 2 + len(value)
        for item in value:
            if depth == 64:
                raise ParsedImportDataError("import_session_json_invalid")
            if isinstance(item, str):
                try:
                    budget[0] -= len(item) if item.isascii() else len(item.encode("utf-8"))
                except UnicodeError:
                    raise ParsedImportDataError("import_session_json_invalid") from None
            elif item is None or type(item) in (bool, int):
                budget[0] -= len(str(item))
            elif type(item) in (dict, list, tuple) and not item:
                budget[0] -= 2
            else:
                _reference_budget(item, budget, depth=depth + 1)
            if budget[0] < 0:
                raise ParsedImportDataError("import_session_draft_too_large")
    elif isinstance(value, str):
        try:
            budget[0] -= len(value) if value.isascii() else len(value.encode("utf-8"))
        except UnicodeError:
            raise ParsedImportDataError("import_session_json_invalid") from None
    elif value is None or type(value) in (bool, int):
        budget[0] -= len(str(value))
    elif type(value) is float:
        if not math.isfinite(value):
            raise ParsedImportDataError("import_session_json_invalid")
        budget[0] -= len(str(value))
    elif isinstance(value, datetime):
        budget[0] -= len(value.isoformat())
    elif type(value) in (ParsedImportSession, ParsedImportFile, ParsedCashDocument, ParsedCashRow, SourceCoverage):
        for field in fields(value):
            _reference_budget(getattr(value, field.name), budget, depth=depth + 1)
    else:
        raise ParsedImportDataError("import_session_json_invalid")
    if budget[0] < 0:
        raise ParsedImportDataError("import_session_draft_too_large")



def _outcome(function, value, budget, depth):
    try:
        result = function(value, budget, depth=depth)
    except Exception as error:
        return type(error), getattr(error, 'code', str(error)), budget
    return result, budget


def _nested(value, count):
    for _ in range(count):
        value = [value]
    return value


_VALUES = [None, True, False, 123, -100000, 1.25, float('nan'), float('inf'), Decimal('1'),
    '', 'ascii', '中文', '\ud800', [], {}, (), {'x': []}, [[]], [float('nan')], {'x': float('nan')},
    {'中文': ['x', None, True, False, 12, {}, (), []]}, {1: []}, _nested([], 65), 10 ** 4500,
    datetime(2026, 1, 1, tzinfo=timezone.utc)]


@pytest.mark.parametrize('value', _VALUES, ids=lambda value: type(value).__name__)
@pytest.mark.parametrize('depth', [0, 63, 64, 65])
@pytest.mark.parametrize('limit', [-1, 0, 1, 4, 20, 1000000])
def test_exact_values_preserve_errors_remaining_budget_and_depth(value, depth, limit):
    assert _outcome(module._json_budget, value, [limit], depth) == _outcome(_reference_budget, value, [limit], depth)


def test_json_budget_keeps_shared_references_cycles_and_budget_aliases():
    shared = {'x': [None, True, '中文']}
    cycle = []; cycle.append(cycle)
    for value in ([shared, shared], cycle):
        for limit in (1, 100, 100000):
            assert _outcome(module._json_budget, value, [limit], 0) == _outcome(_reference_budget, value, [limit], 0)
    for function in (module._json_budget, _reference_budget):
        budget = [100]
        outcome = _outcome(function, {'budget': budget}, budget, 0)
        if function is module._json_budget:
            actual = outcome
        else:
            assert actual == outcome


def test_json_budget_preserves_subclass_callbacks_and_observed_budget():
    def run(function):
        budget, calls = [1000], []
        class Text(str):
            def isascii(self):
                calls.append(('ascii', budget[0])); return super().isascii()
        class Items(dict):
            def items(self):
                calls.append(('items', budget[0])); return super().items()
        value = {'prefix': [None, True], 'special': Items({Text('key'): Text('中文')})}
        result = _outcome(function, value, budget, 0)
        return result, calls
    assert run(module._json_budget) == run(_reference_budget)


def test_ordinary_json_does_not_reenter_public_budget_for_each_container(monkeypatch):
    original, calls = module._json_budget, []
    def counted(*args, **kwargs):
        calls.append(1); return original(*args, **kwargs)
    monkeypatch.setattr(module, '_json_budget', counted)
    module._json_budget({'rows': [{'fields': {'amount': '-1', 'note': None}} for _ in range(100)]}, [100000])
    assert len(calls) == 1


def test_random_nested_json_matches_reference():
    randomizer = random.Random(101)
    def tree(depth):
        if depth == 0 or randomizer.randrange(3) == 0:
            return randomizer.choice([None, True, False, 7, -7, 0.125, '中文', 'ascii'])
        items = [tree(depth - 1) for _ in range(randomizer.randrange(4))]
        return {str(index): value for index, value in enumerate(items)} if randomizer.randrange(2) else items
    for _ in range(100):
        value = tree(5)
        for limit in (0, 10, 100, 100000):
            assert _outcome(module._json_budget, value, [limit], 0) == _outcome(_reference_budget, value, [limit], 0)


def test_custom_depth_preserves_comparison_protocol():
    def run(function):
        calls = []
        class Depth(int):
            def __gt__(self, other):
                calls.append(other); return super().__gt__(other)
        return _outcome(function, Decimal('1'), [100], Depth(0)), calls
    assert run(module._json_budget) == run(_reference_budget)
