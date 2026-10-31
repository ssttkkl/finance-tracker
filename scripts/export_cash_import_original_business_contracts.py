"""Prepare anonymous original business-plan inputs and persisted states, without expected plans."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.cash_statement_rows import ParsedCashRow
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashTransactionModel, CashTransactionComponentModel
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_operations import _file, _as_source, _bind_existing
from tests.contract.test_cash_import_business_plan import _mirror_files, _existing_mirror, _open_transfer
from tests.contract.test_parsed_cash_import_context import USER

SCENARIOS = ("base", "bound", "existing_mirror", "wechat", "truncated", "open", "open_change",
    "open_accept_true", "open_accept_false", "open_ignore_true", "open_ignore_false",
    "old_alipay", "old_wechat", "negative_other", "negative_bank", "negative_platform", "negative_blank", "pruning")


def wechat_files(same=True):
    expense = _file(provider="wechat-order")
    expense = replace(expense, source_type="wechat", source_scope_label="来源甲", document=replace(expense.document,
        source_type="wechat", rows=(replace(expense.document.rows[0], source_explanation={
            "txn_id": "wechat-order", "merchant_order_id": "merchant-key"}),)))
    refund = _file(letter="b", amount="5", provider="merchant-key")
    row = refund.document.rows[0]
    refund = replace(refund, source_type="wechat", source_scope_label="来源甲" if same else "来源乙",
        document=replace(refund.document, source_type="wechat", rows=(replace(row,
            fields={**row.fields, "record_type": "refund"}, source_explanation={"txn_id": "merchant-key",
                "txn_type": "商户消费-退款", "status": "退款成功", "direction": "收入"}),)))
    return expense, refund


def bundle(amount):
    a, b = _mirror_files()
    a = replace(a, document=replace(a.document, rows=(replace(a.document.rows[0], source_explanation={"txn_id": "order1"}),)))
    c = _file(letter="c", amount=amount, provider="003")
    c = replace(c, document=replace(c.document, rows=(replace(c.document.rows[0],
        fields={**c.document.rows[0].fields, "record_type": "refund"},
        source_explanation={"txn_id": "order1_refund", "_is_refund": True}),)))
    d = replace(c, file_ref="file_d", client_file_ref="client_d", digest="d" * 64, source_type="icbc_debit",
        document=replace(c.document, source_type="icbc_debit", rows=(replace(c.document.rows[0], provider_id="004",
            source_explanation={"_is_refund": True, "_icbc_refund_merchant_trusted": True}),)))
    return a, b, c, _as_source(d, "icbc_debit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    runtime = SimpleNamespace(workspace_id=config["workspace"])
    engine = create_relational_engine(args.database_url)
    special = None
    try:
        with create_session_factory(engine).begin() as session:
            name = args.scenario
            if name == "bound":
                _bind_existing(session, runtime, _file())
            elif name == "existing_mirror":
                _existing_mirror(session, runtime)
            elif name.startswith("open"):
                with pytest.MonkeyPatch.context() as patch:
                    special = _open_transfer(session, runtime, patch)
            elif name == "wechat":
                expense, _ = wechat_files()
                _bind_existing(session, runtime, expense)
                session.get(CashTransactionModel, 1003).relation_metadata = expense.document.rows[0].source_explanation
            elif name.startswith("old_"):
                source = name.removeprefix("old_")
                explanation = {"txn_id": "long-order"} if source == "alipay" else {"txn_id": "wechat-order", "merchant_order_id": "merchant-key"}
                file = _file(provider=explanation["txn_id"])
                row = replace(file.document.rows[0], fields={**file.document.rows[0].fields, "occurred_at": "2025-01-01T00:00:00Z"}, source_explanation=explanation)
                _bind_existing(session, runtime, replace(file, source_type=source, document=replace(file.document, source_type=source, rows=(row,))))
                session.get(CashTransactionModel, 1003).relation_metadata = explanation
                incoming_explanation = {"txn_id": "long-order_refund"} if source == "alipay" else {"txn_id": "merchant-key", "txn_type": "商户消费-退款", "status": "退款成功", "direction": "收入"}
                incoming = _file(letter="b", amount="5", provider=incoming_explanation["txn_id"])
                special = replace(incoming, source_type=source, document=replace(incoming.document, source_type=source,
                    rows=(replace(incoming.document.rows[0], fields={**incoming.document.rows[0].fields, "record_type": "refund"}, source_explanation=incoming_explanation),)))
            elif name.startswith("negative_"):
                _bind_existing(session, runtime, _file())
                parent = session.get(CashTransactionModel, 1003)
                parent.source_type = {"negative_other": "fixture-other", "negative_bank": "icbc_debit", "negative_platform": "alipay", "negative_blank": "   "}[name]
                parent.record_origin, parent.record_type = "import", "consumption"
            elif name == "pruning":
                _bind_existing(session, runtime, _file(provider="old-payment"))
                parent = session.get(CashTransactionModel, 1003)
                parent.source_type, parent.record_origin = "merchant-export", "import"
                parent.record_type, parent.record_subtype, parent.amount = "refund", "not_applicable", Decimal("12.5")
                parent.note, parent.counterparty = "原订单退款", "合成商户"
                session.get(CashTransactionComponentModel, 1003).amount = parent.amount
                special = _file(provider="new-payment")
            elif name == "truncated":
                bank = session.get(CashTransactionModel, 1003)
                bank.source_type, bank.counterparty = "ccb_debit", "银行摘要"
                bank.record_type, bank.record_subtype = "consumption", "not_applicable"
                when = datetime(2026, 7, 5, tzinfo=timezone.utc)
                bank.occurred_at = when
                for index in range(20):
                    identifier = 2001 + index
                    session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id, account_id=101,
                        amount=Decimal("-12.5"), currency="CNY", occurred_at=when, record_type="consumption",
                        record_subtype="not_applicable", source_type="wechat", record_id=f"candidate-{index:03d}", counterparty="合成商户"))
                    session.flush()
                    session.add(CashTransactionComponentModel(id=identifier, workspace_id=runtime.workspace_id,
                        cash_transaction_id=identifier, account_id=101, amount=Decimal("-12.5"), currency="CNY", ordinal=0))
                    old = _file(provider=f"candidate-{index:03d}", letter=f"{index:02x}")
                    old = replace(old, source_type="wechat", digest=f"{index+1:064x}", document=replace(old.document, source_type="wechat"))
                    CashSourceRepository(session, runtime.workspace_id).save_occurrence(identifier, next(iter(source_batch_rows((old,)).values())))
                special = _file(provider="zz-last")
                special = replace(special, source_type="wechat", document=replace(special.document, source_type="wechat"))
    finally:
        engine.dispose()
    file = _file()
    row = file.document.rows[0]
    variants = {"default": (file,), "mirror": _mirror_files(), "duplicate": (file,),
        "wallet": (replace(file, document=replace(file.document, rows=(replace(row, payment_method="", fields={**row.fields,
            "source_payload": {**row.fields["source_payload"], "商品说明": ""}}),))),),
        "changed": (_file(note="来源更新备注"),), "ordinary": (_file(letter="c", name="来源新名称"),),
        "evidence": (replace(file, document=replace(file.document, rows=(replace(row, fields={**row.fields,
            "source_payload": {"synthetic": "synthetic-unique-evidence-" + "x" * 10000}}),))),),
        "conflicts": (_file(letter="b", amount="-99"), _file(letter="c", provider="002"),
            _file(letter="d", rows=(ParsedCashRow(1, None, disposition="skipped", error_code="row_unreadable"),))),
        "conflict_only": (_file(letter="b", amount="-99"),),
        "unidentified": tuple(replace(f, document=replace(f.document, rows=tuple(replace(r, provider_id=None) for r in f.document.rows))) for f in _mirror_files()),
        "bundle_partial": bundle("5"), "bundle_full": bundle("12.5"),
        "candidate_members": (*_mirror_files()[:1], _file(letter="c", provider="another-payment"), _mirror_files()[1]),
        "independent": (*_mirror_files(), _file(letter="c", amount="-7", provider="003"), _as_source(_file(letter="d", amount="-7", provider="004"), "icbc_debit"))}
    for same in (True, False):
        expense, refund = wechat_files(same)
        variants[f"wechat_{str(same).lower()}"] = expense, refund
        variants[f"wechat_refund_{str(same).lower()}"] = (refund,)
        files = []
        for letter, source, provider in (("a", "alipay", "platform-a"), ("b", "alipay", "platform-b"), ("c", "ccb_debit", None), ("d", "ccb_debit", None)):
            f = _file(letter=letter, provider=provider, note=f"实际出现{letter}", name="合成商户" if same or source == "alipay" else f"银行摘要{letter}")
            files.append(_as_source(f, source) if source == "ccb_debit" else replace(f, source_type=source, document=replace(f.document, source_type=source)))
        bank = files[2]
        bank = replace(bank, document=replace(bank.document, rows=(bank.document.rows[0], replace(files[3].document.rows[0], position=2)), declared_count=2, observed_count=2))
        variants[f"ambiguous_{str(same).lower()}"] = (*files[:2], bank)
    many = []
    for f in _mirror_files():
        rows = tuple(replace(f.document.rows[0], position=i+1, provider_id=str(i+1), fields={**f.document.rows[0].fields,
            "amount": str(-i-1), "source_payload": {"交易号": str(i+1)}}) for i in range(20))
        many.append(replace(f, document=replace(f.document, rows=rows, declared_count=20, observed_count=20)))
    variants["many"] = tuple(many)
    if special is not None:
        variants["special"] = (special,)
        if args.scenario.startswith("open"):
            variants["competing"] = (special, replace(special, file_ref="file_b", client_file_ref="client_b", digest="b" * 64,
                document=replace(special.document, rows=(replace(special.document.rows[0], provider_id="another-transfer",
                    fields={**special.document.rows[0].fields, "source_payload": {"交易号": "another-transfer"}}),))))
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    inputs = {}
    for key, files in variants.items():
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=files)
        inputs[key] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    (output / "api-input.json").write_text(json.dumps(inputs, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
