"""Prepare original manual-transfer maintenance business-plan checkpoints."""
from __future__ import annotations

import export_current_cash_maintenance_contracts as contracts
import tests.contract.test_cash_entry_editing as entries

SCENARIOS = {
    **{f"transfer_financial_{int(exchange)}": {
        "module": "entries", "method": "test_manual_transfer_finances_change_both_sides_atomically",
        "params": {"exchange": exchange}, "checkpoint": 1,
    } for exchange in (False, True)},
    "transfer_orphan": {
        "module": "entries", "method": "test_orphan_manual_transfer_does_not_allow_single_side_financial_edit",
        "params": {}, "checkpoint": 2,
    },
}

if __name__ == "__main__":
    contracts.MODULES["entries"] = entries
    contracts.SCENARIOS = SCENARIOS
    contracts.main()
