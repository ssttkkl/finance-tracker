# Unified Snapshot Implementation Plan

Goal: Single snapshot.yaml for all account types.
Architecture: snapshot.py module + update on writes + read on queries.
Spec: docs/superpowers/specs/2026-06-12-unified-snapshot-design.md

New:
  src/ft/snapshot.py — load_save/get_balance/set_balance/update_balance
  tests/test_snapshot.py

Modify:
  src/ft/stock.py — use snapshot.py instead of own SNAPSHOT_PATH
  src/ft/append.py — update snapshot after CSV write
  src/ft/report.py — networth reads from snapshot
  src/ft/acct.py — _compute_balance reads from snapshot
  src/ft/cli.py — checkin/transfer update snapshot
  src/ft/transfer.py — update snapshot after write

Delete: ~/.ft/snapshot_security.yaml (migrate with ft verify --fix)

Tasks:
1. snapshot.py + tests (6 tests: default/roundtrip/get/set/update/nonexistent)
2. stock.py -> use snapshot.py
3. append.py -> update snapshot
4. report.py -> networth from snapshot
5. acct.py -> balance from snapshot
6. cli.py + transfer.py -> update snapshot on write
7. migrate: rm snapshot_security.yaml, ft verify --fix
