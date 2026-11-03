"""Export original sync HTTP validation, response and forwarding behavior."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch


def export(output):
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location("original_cli_contract", root / "tests/contract/test_cli_replacement_api.py")
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    cases = []
    base = dict(source="kraken", account="Broker")
    inputs = [base, dict(provider="okx", account="Broker"), dict(base, provider="okx"), dict(source=" \t", provider="binance", account=" Broker\u3000"), dict(source=None, provider="polymarket", account="Broker")]
    for field, values in {"source": [1, True, [], {}], "provider": [None, 1, False, [], {}, "", " "], "account": [None, 1, False, [], {}, "", " "], "batch_size": [0, -1, True, False, None, [], {}, 1.0, 1.1, "1", 1, 2**63, 10**100], "full": [False, True, "false", "", 0, 1, None, [], [1], {}, {"x": 1}]}.items():
        for value in values:
            body = dict(base)
            if field == "provider": body.pop("source")
            body[field] = value; inputs.append(body)
    inputs += [[], None, 1, "text", {}, dict(source="kraken"), dict(account="Broker")]
    for body in inputs:
        operations = module._Operations(); captured = []
        class Connector: pass
        connector = Connector()
        def factory(provider): captured.append(provider); return connector
        with patch("ft.web.operation_routes._connector", side_effect=factory), module._client(operations) as client:
            raw = json.dumps(body, ensure_ascii=True)
            response = client.post("/api/v1/operations/sync", content=raw, headers={"content-type": "application/json"})
        forwarded = [{key: value for key, value in call[1].items() if key != "connector"} for call in operations.calls if call[0] == "sync"]
        cases.append(dict(raw=raw, status=response.status_code, body=response.json(), providers=captured, forwarded=forwarded))
    output.write_text(json.dumps(cases, ensure_ascii=True, indent=2)+'\n');print(len(cases),'original HTTP cases')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument("--output",type=Path,required=True)
    export(parser.parse_args().output)
