"""Capture only anonymous inputs of original investment replay tests."""
from __future__ import annotations
import argparse
from copy import deepcopy
from hashlib import sha256
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch
from ft.domain import investment_projection as projection

FILES = ["tests/unit/domain/test_investment_event_replay.py", "tests/unit/domain/test_investment_base_currency_cost.py", "tests/test_application_investment.py"]
APP_TESTS = {"test_investment_projection_deducts_commission_from_cash_settlement_and_rejects_cost_currency_mix", "test_investment_projection_soft_start_oversell_and_numeric_overflow"}


def export(output: Path):
    root = Path(__file__).resolve().parents[1]
    if output.exists() or output.resolve().is_relative_to(root):
        raise ValueError("output must be a new file outside the repository")
    cases = {}; original = projection.apply_investment_event
    for index, path in enumerate(FILES):
        spec = importlib.util.spec_from_file_location(f"investment_input_{index}", root/path)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        for name in sorted(vars(module)):
            if not name.startswith("test_") or path == FILES[-1] and name not in APP_TESTS:
                continue
            params = [None] if name != "test_historical_investment_record_types_are_rejected" else ["deposit","withdraw","swap","dividend","fee","checkin"]
            for param in params:
                snapshots = []; refs = []; calls = []
                def capture(snapshot, row, **kwargs):
                    slot = next((i for i, ref in enumerate(refs) if ref is snapshot), None)
                    if slot is None:
                        slot = len(refs); refs.append(snapshot); snapshots.append(deepcopy(snapshot))
                    calls.append({"snapshot":slot,"row":deepcopy(row),"default_currency":kwargs["default_currency"],"base_tickers":sorted(kwargs["base_tickers"]) if kwargs.get("base_tickers") is not None else None})
                    return original(snapshot,row,**kwargs)
                function = getattr(module,name)
                with patch.object(projection,"apply_investment_event",capture), patch.object(module,"apply_investment_event",capture,create=True):
                    function() if param is None else function(param)
                if calls:
                    key = name + (f"[{param}]" if param is not None else "")
                    cases[key] = {"snapshots":snapshots,"calls":calls}
    paths = [*FILES,"src/ft/domain/investment_projection.py","src/ft/domain/investment_record_type.py","src/ft/domain/decimal.py"]
    output.write_text(json.dumps({"sources_sha256":{p:sha256((root/p).read_bytes()).hexdigest() for p in paths},"inputs_only":True,"cases":cases},ensure_ascii=False,indent=2)+"\n")
    print(f"Captured {len(cases)} original input sequences; no expected results exported")

if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("--output",type=Path,required=True);export(parser.parse_args().output)
