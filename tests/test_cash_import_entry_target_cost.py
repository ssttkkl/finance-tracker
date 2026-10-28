from collections import defaultdict
from types import SimpleNamespace

from ft.application.cash_import_entry_choices import entry_target, entry_target_index


class Counted:
    def __init__(self, values):
        self.values, self.iterations = values, 0

    def __iter__(self):
        self.iterations += 1
        return iter(self.values)


def test_independent_entry_targets_scan_global_versions_and_groups_once():
    ids = [str(value) for value in range(1, 1001)]
    rows = Counted([(int(value), 1) for value in reversed(ids)])
    entries = Counted([(int(value), "entry-" + value, 1, "unchanged") for value in ids])
    relations = Counted([])
    old_edges = Counted([])
    groups = Counted([{"suggestion_id": "group-" + value, "action": "defer", "parent_ids": [value]} for value in ids])
    snapshot = SimpleNamespace(context=SimpleNamespace(transaction_revisions=rows, entry_versions=entries,
        relation_revisions=relations, relations=old_edges), parent_ops={}, edges={}, incident=defaultdict(set))
    index = entry_target_index(snapshot, groups)
    refs = []
    for value in ids:
        ref, target = entry_target(snapshot, [value], index)
        refs.append(ref)
        assert target["parent_ids"] == [value]
        assert target["rows"] == [(int(value), 1)]
        assert target["relation_dependencies"] == [{"suggestion_id": "group-" + value, "action": "defer"}]
    assert len(set(refs)) == 1000
    assert all(sequence.iterations == 1 for sequence in (rows, entries, relations, old_edges, groups))
    _, combined = entry_target(snapshot, ["1", "10", "9"], index)
    assert combined["rows"] == [(10, 1), (9, 1), (1, 1)]
    assert [item[0] for item in combined["entries"]] == [1, 9, 10]
