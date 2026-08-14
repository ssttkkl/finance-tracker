import pytest


def _row(source_type: str, **values) -> dict:
    row = {
        "source_type": source_type,
        "bill_source": source_type,
        "currency": "CNY",
        "amount": "-1.00",
        "payment_method": "",
        "counterparty_account": "对方账号-不应作为来源账户",
        "source_payload": {"原始": "fixture"},
    }
    row.update(values)
    return row


@pytest.mark.parametrize(
    ("source_type", "values", "identity_kind", "source_key"),
    [
        ("alipay", {"payment_method": "账户余额"}, "payment_method", "账户余额"),
        ("wechat", {"payment_method": "零钱"}, "payment_method", "零钱"),
        ("icbc_credit", {"card_number": "1200"}, "card_tail", "1200"),
        ("icbc_debit", {"payment_method": "银行卡"}, "file_account", "银行卡"),
        ("ccb_debit", {"card_number": "2820"}, "card_tail", "2820"),
        (
            "icbc_asia",
            {"_source_account_identifier": "1234567890", "card_number": "7890", "currency": "HKD"},
            "account_identifier",
            "1234567890",
        ),
    ],
)
def test_scan_source_rows_extracts_declared_identity_for_each_cash_channel(
    source_type, values, identity_kind, source_key,
):
    from ft.application.statement_account_mapping import scan_source_rows

    groups = scan_source_rows([_row(source_type, **values)])

    assert len(groups) == 1
    assert groups[0].identity_kind == identity_kind
    assert groups[0].source_account_key == source_key
    assert groups[0].row_count == 1
    assert groups[0].currencies == (values.get("currency", "CNY"),)
    assert groups[0].masked_evidence
    assert "对方账号-不应作为来源账户" not in groups[0].masked_evidence


def test_scan_keeps_same_display_name_with_different_stable_identity_separate():
    from ft.application.statement_account_mapping import scan_source_rows

    groups = scan_source_rows([
        _row("ccb_debit", card_number="2820", source_display_name="建设银行"),
        _row("ccb_debit", card_number="0523", source_display_name="建设银行"),
    ])

    assert [(group.display_name, group.source_account_key) for group in groups] == [
        ("建设银行", "2820"),
        ("建设银行", "0523"),
    ]


def test_scan_rejects_business_row_without_a_declared_source_identity():
    from ft.application.statement_account_mapping import scan_source_rows

    with pytest.raises(ValueError, match="来源账户"):
        scan_source_rows([_row("alipay", payment_method="")])


def test_wechat_wallet_deposit_placeholder_is_normalized_before_account_scan(tmp_path, monkeypatch):
    from ft.adapters.statement_import import StatementParser
    from ft.domain.imports import StatementImportCommand

    source = tmp_path / "wechat.xlsx"
    source.write_text("fixture", encoding="utf-8")

    def fake_prepare(_path, _source, _password):
        return ([{
            "date": "2026-08-14 10:00:00",
            "amount": "1.00",
            "currency": "CNY",
            "payment_method": "/",
            "status": "已存入零钱",
            "platform_status": "已存入零钱",
            "record_type": "transfer_in",
            "counterparty": "微信",
            "_source_payload": {"支付方式": "/", "当前状态": "已存入零钱"},
        }], "wechat", [])

    monkeypatch.setattr("ft.convert._prepare_convert_rows", fake_prepare)
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda row, **kwargs: {
            "date": row["date"], "amount": row["amount"], "currency": row["currency"],
            "payment_method": row["payment_method"], "status": row["status"],
            "platform_status": row["platform_status"], "record_type": row["record_type"],
            "counterparty": row["counterparty"], "bill_source": "wechat", "source_type": "wechat",
        },
    )

    rows = StatementParser().parse_source_rows(
        StatementImportCommand(source_path=str(source), source="wechat")
    )

    assert rows[0]["payment_method"] == "零钱"


def test_alipay_missing_payment_method_uses_explicit_wallet_group(tmp_path, monkeypatch):
    from ft.adapters.statement_import import StatementParser
    from ft.domain.imports import StatementImportCommand

    source = tmp_path / "alipay.csv"
    source.write_text("fixture", encoding="utf-8")

    def fake_prepare(_path, _source, _password):
        return ([{
            "date": "2026-08-14 10:00:00",
            "amount": "1.00",
            "currency": "CNY",
            "payment_method": "",
            "record_type": "income",
            "counterparty": "商户",
            "_source_payload": {"收/付款方式": "", "金额": "1.00"},
        }], "alipay", [])

    monkeypatch.setattr("ft.convert._prepare_convert_rows", fake_prepare)
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda row, **kwargs: {
            "date": row["date"], "amount": row["amount"], "currency": row["currency"],
            "payment_method": row["payment_method"], "record_type": row["record_type"],
            "counterparty": row["counterparty"], "bill_source": "alipay", "source_type": "alipay",
        },
    )

    rows = StatementParser().parse_source_rows(
        StatementImportCommand(source_path=str(source), source="alipay")
    )

    assert rows[0]["payment_method"] == "支付宝余额"


def test_icbc_credit_missing_card_number_is_recovered_from_source_snapshot(tmp_path, monkeypatch):
    from ft.adapters.statement_import import StatementParser
    from ft.domain.imports import StatementImportCommand

    source = tmp_path / "icbc.pdf"
    source.write_text("fixture", encoding="utf-8")

    def fake_prepare(_path, _source, _password):
        return ([{
            "date": "2026-08-14 10:00:00",
            "amount": "-1.00",
            "currency": "CNY",
            "card_number": "",
            "payment_method": "银行卡",
            "record_type": "consumption",
            "counterparty": "商户",
            "_source_payload": {"原始文本单元": [
                "2026-08-14", "10:00:00", "379983032529166", "借",
            ]},
        }], "icbc_credit", [])

    monkeypatch.setattr("ft.convert._prepare_convert_rows", fake_prepare)
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda row, **kwargs: {
            "date": row["date"], "amount": row["amount"], "currency": row["currency"],
            "card_number": row["card_number"], "payment_method": row["payment_method"],
            "record_type": row["record_type"], "counterparty": row["counterparty"],
            "bill_source": "icbc_credit", "source_type": "icbc_credit",
        },
    )

    rows = StatementParser().parse_source_rows(
        StatementImportCommand(source_path=str(source), source="icbc")
    )

    assert rows[0]["card_number"] == "9166"


def test_scan_group_id_is_opaque_and_does_not_contain_the_source_key():
    from ft.application.statement_account_mapping import scan_source_rows

    group = scan_source_rows([_row("alipay", payment_method="账户余额")])[0]

    assert group.group_id
    assert "账户余额" not in group.group_id


def test_statement_mapping_upsert_is_workspace_scoped_and_versioned():
    from sqlalchemy import select

    from ft.adapters.relational.models import AccountModel, StatementAccountMappingModel
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "支付宝", "type": "cash", "currency": "CNY"})
        uow.commit()
    with sessions() as session:
        account_id = session.scalar(
            select(AccountModel.id).where(
                AccountModel.workspace_id == "workspace-a", AccountModel.name == "支付宝",
            )
        )

    with unit_of_work(sessions, "workspace-a") as uow:
        mapping = uow.statement_account_mappings.upsert(
            source_type="alipay", identity_kind="payment_method",
            source_account_key="账户余额", account_id=account_id, confirmed_by="web",
        )
        assert mapping["revision"] == 1
        uow.commit()

    with unit_of_work(sessions, "workspace-a") as uow:
        found = uow.statement_account_mappings.get(
            source_type="alipay", identity_kind="payment_method", source_account_key="账户余额",
        )
        changed = uow.statement_account_mappings.upsert(
            source_type="alipay", identity_kind="payment_method",
            source_account_key="账户余额", account_id=account_id, confirmed_by="web",
            expected_revision=found["revision"],
        )
        assert changed["revision"] == 2
        uow.commit()

    with unit_of_work(sessions, "workspace-b") as uow:
        assert uow.statement_account_mappings.get(
            source_type="alipay", identity_kind="payment_method", source_account_key="账户余额",
        ) is None
        uow.commit()


def test_mapping_suggestion_prefers_history_over_conflicting_aliases_and_exposes_currency_draft():
    from sqlalchemy import select

    from ft.adapters.relational.models import AccountModel
    from ft.application.statement_account_mapping import scan_source_rows, suggest_mapping
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "历史账户", "type": "cash", "currency": "CNY"})
        uow.accounts.add_raw({"name": "别名账户", "type": "cash", "currency": "CNY"})
        uow.commit()
    with sessions() as session:
        accounts = {
            row.name: row.id for row in session.scalars(
                select(AccountModel).where(AccountModel.workspace_id == "workspace-a")
            )
        }
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.account_aliases.add(alias_type="payment_method", alias_value="账户余额", account_id=accounts["别名账户"])
        # The production alias contract only permits card/account identifiers;
        # this fixture uses card_tail to create a deliberate competing target.
        uow.account_aliases.add(alias_type="card_tail", alias_value="1200", account_id=accounts["别名账户"])
        uow.statement_account_mappings.upsert(
            source_type="ccb_debit", identity_kind="card_tail", source_account_key="1200",
            account_id=accounts["历史账户"], confirmed_by="web",
        )
        group = scan_source_rows([_row("ccb_debit", card_number="1200", currency="USD")])[0]
        suggestion = suggest_mapping(uow, group)
        assert suggestion["account_id"] == accounts["历史账户"]
        assert suggestion["missing_currencies"] == ("USD",)
        assert suggestion["mapping_revision"] == 1
        uow.commit()


def test_mapping_suggestion_uses_only_one_active_alias_target():
    from ft.adapters.relational.models import AccountModel
    from ft.application.statement_account_mapping import scan_source_rows, suggest_mapping
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "唯一别名账户", "type": "cash", "currency": "CNY"})
        uow.accounts.add_raw({"name": "停用账户", "type": "cash", "currency": "CNY", "active": False})
        uow.commit()
    with sessions() as session:
        account_rows = list(session.query(AccountModel).filter(AccountModel.workspace_id == "workspace-a"))
    active_id = next(row.id for row in account_rows if row.name == "唯一别名账户")
    inactive_id = next(row.id for row in account_rows if row.name == "停用账户")
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.account_aliases.add(alias_type="card_tail", alias_value="2820", account_id=active_id)
        group = scan_source_rows([_row("ccb_debit", card_number="2820")])[0]
        assert suggest_mapping(uow, group)["account_id"] == active_id
        uow.account_aliases.add(alias_type="card_tail", alias_value="0523", account_id=inactive_id)
        inactive_group = scan_source_rows([_row("ccb_debit", card_number="0523")])[0]
        assert suggest_mapping(uow, inactive_group)["account_id"] is None
        uow.commit()


def test_new_account_draft_is_explicit_and_contains_source_currency():
    from ft.application.statement_account_mapping import new_account_draft, scan_source_rows

    group = scan_source_rows([_row("alipay", payment_method="花呗", currency="CNY")])[0]
    draft = new_account_draft(group)

    assert draft == {
        "name": "花呗",
        "type": "loan",
        "currencies": ["CNY"],
    }


def test_source_parser_path_does_not_read_yaml_or_assign_a_system_account(tmp_path, monkeypatch):
    from ft.adapters.statement_import import StatementParser
    from ft.domain.imports import StatementImportCommand

    source = tmp_path / "statement.csv"
    source.write_text("fixture", encoding="utf-8")

    def fake_prepare(_path, _source, _password):
        return ([{
            "date": "2026-08-14",
            "amount": "-1.00",
            "currency": "CNY",
            "payment_method": "账户余额",
            "counterparty": "商户",
            "counterparty_account": "对方账号",
            "category": "expense",
            "_source_payload": {"原始": "fixture"},
        }], "alipay", [])

    monkeypatch.setattr("ft.convert._prepare_convert_rows", fake_prepare)
    monkeypatch.setattr(
        "ft.convert._build_output_row",
        lambda row, **kwargs: {
            "date": row["date"], "amount": row["amount"], "currency": row["currency"],
            "payment_method": row["payment_method"], "counterparty": row["counterparty"],
            "counterparty_account": row["counterparty_account"],
            "category": row["category"], "bill_source": "alipay", "source_type": "alipay",
            "source_payload": row["_source_payload"],
        },
    )
    monkeypatch.setattr("ft.mapping.load_rules", lambda: (_ for _ in ()).throw(AssertionError("YAML read")))

    rows = StatementParser().parse_source_rows(
        StatementImportCommand(source_path=str(source), source="alipay")
    )

    assert rows[0]["account_name"] == ""
    assert rows[0]["payment_method"] == "账户余额"


def test_database_mapped_parser_uses_confirmed_mapping_without_yaml(tmp_path, monkeypatch):
    from sqlalchemy import select

    from ft.application.statement_account_mapping import DatabaseMappedStatementParser
    from ft.domain.imports import StatementImportCommand
    from ft.adapters.relational import ensure_workspace
    from ft.adapters.relational.models import AccountModel
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [_row("alipay", payment_method="账户余额")]

    source = tmp_path / "statement.csv"
    source.write_text("fixture", encoding="utf-8")
    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "cli-mapping-workspace")
    with unit_of_work(sessions, "cli-mapping-workspace") as uow:
        uow.accounts.add_raw({"name": "数据库账户", "type": "cash", "currency": "CNY"})
        uow.commit()
    with sessions() as session:
        account_id = session.scalar(select(AccountModel.id).where(
            AccountModel.workspace_id == "cli-mapping-workspace", AccountModel.name == "数据库账户",
        ))
    with unit_of_work(sessions, "cli-mapping-workspace") as uow:
        uow.statement_account_mappings.upsert(
            source_type="alipay", identity_kind="payment_method", source_account_key="账户余额",
            account_id=account_id, confirmed_by="web",
        )
        uow.commit()
    monkeypatch.setattr("ft.mapping.load_rules", lambda: (_ for _ in ()).throw(AssertionError("YAML read")))

    rows = DatabaseMappedStatementParser(
        SourceParser(), unit_of_work(sessions, "cli-mapping-workspace")
    ).parse(StatementImportCommand(source_path=str(source), source="alipay"))

    assert rows[0]["account_name"] == "数据库账户"


def test_database_mapped_parser_fails_closed_when_mapping_is_missing(tmp_path):
    from ft.application.statement_account_mapping import DatabaseMappedStatementParser
    from ft.adapters.relational import ensure_workspace
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [_row("alipay", payment_method="未确认账户")]

    source = tmp_path / "statement.csv"
    source.write_text("fixture", encoding="utf-8")
    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "missing-cli-mapping-workspace")

    with pytest.raises(ValueError, match="来源账户尚未完成映射"):
        DatabaseMappedStatementParser(
            SourceParser(), unit_of_work(sessions, "missing-cli-mapping-workspace")
        ).parse(StatementImportCommand(source_path=str(source), source="alipay"))


def test_scan_reports_missing_source_identity_without_falling_back_to_yaml(tmp_path):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from test_postgres_adapter import _database

    class MissingIdentityParser:
        def parse_source_rows(self, _command):
            return [{
                "bill_source": "alipay", "source_type": "alipay", "payment_method": "",
                "currency": "CNY", "amount": "-1.00", "_source_payload": {"row": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "missing-source-identity-workspace")
    service = CashLedgerCommandService(
        sessions, "missing-source-identity-workspace", parser=MissingIdentityParser(),
    )

    with pytest.raises(ValueError, match="import_source_account_unrecognized"):
        service.scan_import(b"fixture", filename="statement.csv")


def test_scan_import_returns_groups_accounts_and_silent_preselection(tmp_path):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, command):
            return [{
                "bill_source": "alipay", "source_type": "alipay", "payment_method": "账户余额",
                "currency": "CNY", "amount": "-1.00", "date": "2026-08-14",
                "counterparty": "商户", "counterparty_account": "对方账号",
                "category": "expense", "_source_payload": {"原始": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "scan-workspace")
    with unit_of_work(sessions, "scan-workspace") as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        uow.commit()
    with sessions() as session:
        from ft.adapters.relational.models import AccountModel
        account_id = session.query(AccountModel.id).filter(
            AccountModel.workspace_id == "scan-workspace", AccountModel.name == "支付宝余额",
        ).scalar()
    with unit_of_work(sessions, "scan-workspace") as uow:
        uow.statement_account_mappings.upsert(
            source_type="alipay", identity_kind="payment_method", source_account_key="账户余额",
            account_id=account_id, confirmed_by="web",
        )
        uow.commit()
    service = CashLedgerCommandService(
        sessions, "scan-workspace", parser=SourceParser(),
    )

    result = service.scan_import(b"fixture", filename="statement.csv")

    assert result["contract"] == "cash-account-mapping-v1"
    assert result["groups"][0]["masked_evidence"]
    assert result["groups"][0]["suggestion"]["account_id"] == account_id
    assert result["groups"][0]["suggestion"]["mapping_revision"] == 1
    assert result["groups"][0]["suggestion"]["missing_currencies"] == []
    assert result["accounts"][0]["id"] == account_id


def test_preview_applies_explicit_mapping_without_creating_accounts_or_expanding_currency(tmp_path):
    from sqlalchemy import func, select

    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [{
                "record_id": "row-usd", "bill_source": "alipay", "source_type": "alipay",
                "payment_method": "账户余额", "currency": "USD", "amount": "-1.00",
                "date": "2026-08-14", "counterparty": "商户", "counterparty_account": "",
                "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                "note": "测试", "_source_payload": {"原始": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "preview-mapping-workspace")
    with unit_of_work(sessions, "preview-mapping-workspace") as uow:
        uow.accounts.add_raw({"name": "多币种账户", "type": "cash", "currency": "CNY"})
        uow.commit()
    with sessions() as session:
        account_id = session.scalar(select(AccountModel.id).where(
            AccountModel.workspace_id == "preview-mapping-workspace",
            AccountModel.name == "多币种账户",
        ))
    service = CashLedgerCommandService(
        sessions, "preview-mapping-workspace", parser=SourceParser(),
    )
    scan = service.scan_import(b"fixture", filename="statement.csv")
    group_id = scan["groups"][0]["group_id"]

    preview = service.preview_import(
        b"fixture", source="", currency=None, filename="statement.csv",
        mapping=[{"group_id": group_id, "account_id": account_id, "mapping_revision": None}],
    )

    assert preview["items"][0]["account_name"] == "多币种账户"
    assert preview["items"][0]["status"] == "new"
    assert preview["mapping"][0]["missing_currencies"] == ["USD"]
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
        assert session.scalar(select(func.count()).select_from(StatementAccountMappingModel)) == 0
        account = session.get(AccountModel, account_id)
        assert account.currencies == ["CNY"]


def test_preview_new_account_draft_does_not_write_account_or_mapping(tmp_path):
    from sqlalchemy import func, select

    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [{
                "record_id": "row-new-preview", "bill_source": "alipay", "source_type": "alipay",
                "payment_method": "花呗", "currency": "CNY", "amount": "-3.00",
                "date": "2026-08-14", "counterparty": "商户", "counterparty_account": "",
                "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                "note": "测试", "_source_payload": {"原始": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "preview-new-account-workspace")
    service = CashLedgerCommandService(
        sessions, "preview-new-account-workspace", parser=SourceParser(),
    )
    scan = service.scan_import(b"fixture", filename="statement.csv")
    group = scan["groups"][0]

    preview = service.preview_import(
        b"fixture", source="", currency=None, filename="statement.csv",
        mapping=[{
            "group_id": group["group_id"],
            "account_id": None,
            "mapping_revision": group["suggestion"]["mapping_revision"],
            "new_account": {"name": "花呗", "type": "loan", "currencies": ["CNY"]},
        }],
    )

    assert preview["mapping"][0]["new_account"] == {
        "name": "花呗", "type": "loan", "currencies": ["CNY"],
    }
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(AccountModel)) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 0
        assert session.scalar(select(func.count()).select_from(StatementAccountMappingModel)) == 0


def test_commit_creates_new_account_mapping_and_cash_row_in_one_final_confirmation(tmp_path):
    from sqlalchemy import select

    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [{
                "record_id": "row-new", "bill_source": "alipay", "source_type": "alipay",
                "payment_method": "花呗", "currency": "CNY", "amount": "-3.00",
                "date": "2026-08-14", "counterparty": "商户", "counterparty_account": "",
                "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                "note": "测试", "_source_payload": {"原始": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "commit-mapping-workspace")
    service = CashLedgerCommandService(
        sessions, "commit-mapping-workspace", parser=SourceParser(),
    )
    scan = service.scan_import(b"fixture", filename="statement.csv")
    group = scan["groups"][0]
    mapping = [{
        "group_id": group["group_id"],
        "mapping_revision": group["suggestion"]["mapping_revision"],
        "new_account": {"name": "花呗", "type": "loan", "currencies": ["CNY"]},
    }]

    result = service.commit_import(
        b"fixture", source="", currency=None, filename="statement.csv",
        preview_digest=scan["digest"], preview_channel=scan["channel"], mapping=mapping,
    )

    assert result["new_rows"] == 1
    with sessions() as session:
        account = session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == "commit-mapping-workspace", AccountModel.name == "花呗",
        ))
        assert account is not None
        assert account.type == "loan"
        assert account.currencies == ["CNY"]
        cash = session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == "commit-mapping-workspace",
        ))
        assert cash is not None
        assert cash.account_id == account.id
        mapping_row = session.scalar(select(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == "commit-mapping-workspace",
        ))
        assert mapping_row is not None
        assert mapping_row.account_id == account.id


def test_commit_failure_rolls_back_draft_account_mapping_and_cash_row(tmp_path):
    from sqlalchemy import func, select

    from ft.adapters.relational.models import AccountModel, CashTransactionModel, StatementAccountMappingModel
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational import ensure_workspace
    from test_postgres_adapter import _database

    class SourceParser:
        def parse_source_rows(self, _command):
            return [{
                "record_id": "row-rollback", "bill_source": "alipay", "source_type": "alipay",
                "payment_method": "花呗", "currency": "CNY", "amount": "-3.00",
                "date": "2026-08-14", "counterparty": "商户", "counterparty_account": "",
                "category": "expense", "record_type": "consumption", "record_subtype": "not_applicable",
                "note": "测试", "_source_payload": {"原始": "fixture"},
            }]

    sessions, unit_of_work = _database()
    ensure_workspace(sessions, "rollback-mapping-workspace")
    service = CashLedgerCommandService(
        sessions, "rollback-mapping-workspace", parser=SourceParser(),
    )
    scan = service.scan_import(b"fixture", filename="statement.csv")
    group = scan["groups"][0]
    mapping = [{
        "group_id": group["group_id"],
        "mapping_revision": None,
        "new_account": {"name": "花呗", "type": "loan", "currencies": ["CNY"]},
    }]

    with pytest.raises(ValueError, match="导入关系类型无效"):
        service.commit_import(
            b"fixture", source="", currency=None, filename="statement.csv",
            preview_digest=scan["digest"], preview_channel=scan["channel"],
            mapping=mapping, relation_decisions=[{"kind": "not-a-relation"}],
        )

    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(AccountModel).where(
            AccountModel.workspace_id == "rollback-mapping-workspace",
        )) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == "rollback-mapping-workspace",
        )) == 0
        assert session.scalar(select(func.count()).select_from(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == "rollback-mapping-workspace",
        )) == 0
