"""Run deterministic file shards of the complete non-performance pytest suite."""
import argparse
from collections import defaultdict
from hashlib import sha256
import json

import pytest


def assign_files(counts, count):
    loads, assigned = [0] * count, {}
    for path in sorted(counts, key=lambda key: (-counts[key], key)):
        shard = min(range(count), key=lambda index: (loads[index], index))
        assigned[path] = shard
        loads[shard] += counts[path]
    return assigned


class FileShard:
    def __init__(self, index, count):
        self.index, self.count = index, count

    @pytest.hookimpl(trylast=True)
    def pytest_collection_modifyitems(self, config, items):
        counts = defaultdict(int)
        for item in items:
            counts[str(item.path)] += 1
        assigned = assign_files(counts, self.count)
        fingerprint = sha256(json.dumps(sorted(counts.items())).encode()).hexdigest()
        selected, deselected = [], []
        for item in items:
            (selected if assigned[str(item.path)] == self.index else deselected).append(item)
        config.hook.pytest_deselected(items=deselected)
        items[:] = selected
        print(f"\nBackend shard {self.index + 1}/{self.count}: {len(selected)}/{sum(counts.values())} tests; collection={fingerprint}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--shard-index', type=int, required=True)
    parser.add_argument('--shard-count', type=int, required=True)
    args = parser.parse_args()
    if args.shard_count < 1 or not 0 <= args.shard_index < args.shard_count:
        parser.error('require 0 <= shard-index < shard-count')
    return pytest.main(['-q', '-m', 'not performance'], plugins=[FileShard(args.shard_index, args.shard_count)])


if __name__ == '__main__':
    raise SystemExit(main())
