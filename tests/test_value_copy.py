from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.copying import copy_value


@pytest.mark.parametrize('value', [None, True, 12, 2.5, '中文', Decimal('1.2300'),
    datetime(2026, 1, 1, tzinfo=timezone.utc), [], {}, [1, {'items': [2, 3]}], (1, [2])])
def test_value_copy_matches_deepcopy_and_isolates_mutable_values(value):
    result = copy_value(value)
    assert result == deepcopy(value)
    if type(value) in (dict, list):
        assert result is not value


def test_value_copy_preserves_cycles_and_cross_container_aliases():
    shared = {'labels': []}
    value = [shared, {'same': shared}]
    shared['cycle'] = value
    result = copy_value(value)
    assert result is not value and result[0] is not shared
    assert result[0] is result[1]['same']
    assert result[0]['cycle'] is result
    result[0]['labels'].append('changed')
    assert shared['labels'] == []


def test_value_copy_preserves_tuple_cycle_and_aliases():
    items = []
    pair = (items,)
    items.append(pair)
    value = [pair, items]
    result = copy_value(value)
    assert result[0][0] is result[1]
    assert result[1][0] is result[0]
    assert result[1] is not items


def test_value_copy_retains_scalar_and_container_subclass_state():
    class Tagged(str):
        pass
    class Items(list):
        pass
    key = Tagged('key')
    key.labels = []
    values = Items([key])
    values.labels = key.labels
    result = copy_value({key: values})
    copied_key = next(iter(result))
    copied_values = result[copied_key]
    assert type(copied_key) is Tagged and type(copied_values) is Items
    assert copied_values[0] is copied_key
    assert copied_values.labels is copied_key.labels
    copied_values.labels.append('changed')
    assert key.labels == []



def test_unknown_copy_protocol_can_share_a_memoized_scalar():
    class MemoWriter:
        def __deepcopy__(self, memo):
            memo[id("after")] = "replacement"
            return "writer"
    value = [MemoWriter(), "after"]
    assert copy_value(value) == deepcopy(value) == ["writer", "replacement"]
