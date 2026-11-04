"""Prepare original anonymous files for four account-suggestion contracts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_account_mappings import _source
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_parsed_cash_import_context import USER


def cases():
    from tests.contract import test_cash_import_account_mappings as original

    definitions = (
        ("alias", original.test_only_exact_unique_eligible_bank_alias_is_suggested),
        ("scope", original.test_alipay_mapping_does_not_cross_source_account),
        ("eligibility", original.test_suggestions_recheck_account_eligibility_without_writes),
        ("legacy", original.test_legacy_mapping_is_only_a_narrow_readonly_hint),
    )
    result = {}
    for family, function in definitions:
        marker, = [mark for mark in function.pytestmark if mark.name == "parametrize"]
        for index, raw in enumerate(marker.args[1]):
            values = raw if isinstance(raw, tuple) else (raw,)
            if family == "alias":
                files = (_source(_file(), "ccb_debit", "1234567890123456"),)
            elif family == "scope":
                other_owner, = values
                first = _source(_file(), "alipay", None if other_owner is None else "fixture-owner")
                files = (first, _source(_file(letter="b", provider="002"), "alipay", other_owner))
            elif family == "eligibility":
                files = (_file(),)
            else:
                source, _kind, _accepted = values
                owner = "1234567890123456" if source in {"icbc_credit", "ccb_debit"} else None
                files = (_source(_file(), source, owner),)
            result[f"{family}-{index}"] = (values, files)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", required=True, choices=cases())
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    values, files = cases()[args.scenario]
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    def data(items):
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=items)
        return {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    inputs = {"default": data(files[:1]), "parameters": values}
    if len(files) > 1:
        inputs["other"] = data(files[1:])
    (output / "api-input.json").write_text(json.dumps(inputs, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
