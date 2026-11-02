"""Export real investment import results and complete anonymous database columns."""
from __future__ import annotations
import argparse
import base64
import json
from pathlib import Path
from sqlalchemy import select, delete, update
from export_kotlin_contracts import export
from export_investment_write_contracts import state, NOW
from export_cash_transfer_contracts import clock
from ft.adapters.relational import create_relational_engine, create_session_factory, models
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.investment_import import InvestmentImportService
from ft.serialization import json_value


def seed(url):
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    with sessions.begin() as session:
        for identifier, name, kind, active, currencies, metadata in [
            (201, '东方证券', 'security', True, [], {}), (202, 'IBKR', 'security', True, ['USD'], {}),
            (203, '嘉信', 'security', True, [], {}), (204, '盈立证券', 'security', True, [], {}),
            (205, 'Crypto', 'crypto', True, ['USD'], {}), (206, 'Cash', 'cash', True, [], {}),
            (207, 'Inactive', 'security', False, ['USD'], {}), (208, 'LegacyBase', 'security', True, ['USD'], {'base_currencies': ['USD', 'HKD']})]:
            session.add(models.AccountModel(id=identifier, workspace_id='fixture-a', name=name, type=kind,
                                           active=active, currencies=currencies, metadata_json=metadata, created_at=NOW, updated_at=NOW))
    return engine, sessions


def run(target, oracle, output):
    root = Path(__file__).resolve().parents[1]
    if target == oracle or not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError('Use distinct new test databases and external output')
    export(target, output)
    export(oracle, output / 'python')
    target_engine, _ = seed(target)
    engine, sessions = seed(oracle)
    service = InvestmentImportService(RelationalUnitOfWork(sessions, 'fixture-a'))
    inputs = [
        ('dfzq', 'dfzq/sample_statement.txt', '东方证券', None),
        ('dfzq', 'dfzq/sample_statement.txt', '东方证券', None),
        ('dfzq', 'dfzq/fee_breakdown_statement.txt', '东方证券', None),
        ('ibkr', 'ibkr/transactions_1y_sample.csv', 'IBKR', None),
        ('schwab', 'schwab/transaction_history_sample.csv', '嘉信', None),
        ('usmart-hk', 'usmart_hk/monthly_sample.txt', '盈立证券', None),
        ('usmart_hk', 'usmart_hk/monthly_sample.txt', '盈立证券', None),
        ('ibkr', 'ibkr/transactions_1y_sample.csv', 'Crypto', 'hkd'),
        ('dfzq', 'dfzq/sample_statement.txt', 'Inactive', None),
        ('dfzq', 'dfzq/sample_statement.txt', 'missing', None),
        ('dfzq', 'dfzq/sample_statement.txt', 'Cash', None),
        ('unsupported', 'dfzq/sample_statement.txt', '东方证券', None),
    ]
    cases = []
    with clock(NOW):
        for source, file, account, currency in inputs:
            path = root / 'tests/fixtures' / file
            before = state(sessions)
            result = service.import_statement(source, path, account, currency)
            after = state(sessions)
            if not result.ok:
                assert before == after
            cases.append({'source': source, 'filename': path.name, 'bytes': base64.b64encode(path.read_bytes()).decode(),
                          'account': account, 'currency': currency, 'expected': json_value(result), 'effects': after})
    with clock(NOW):
        for metadata in [{'base_currencies': ['USD']}, {'base_currencies': {'USD': True}}, {'base_currencies': 'USD'},
                         {'base_currencies': 17}, {'base_currencies': True}, {'base_currencies': {}}, {'base_currencies': None}]:
            with sessions.begin() as session:
                session.execute(delete(models.InvestmentEventModel).where(models.InvestmentEventModel.workspace_id == 'fixture-a'))
                session.execute(delete(models.LedgerSnapshotModel).where(models.LedgerSnapshotModel.workspace_id == 'fixture-a'))
                session.execute(update(models.AccountModel).where(models.AccountModel.id == 202).values(metadata_json=metadata))
            path = root / 'tests/fixtures/ibkr/transactions_1y_sample.csv'
            before = state(sessions)
            result = service.import_statement('ibkr', path, 'IBKR')
            after = state(sessions)
            if not result.ok:
                assert before == after
            cases.append({'source': 'ibkr', 'filename': path.name, 'bytes': base64.b64encode(path.read_bytes()).decode(),
                          'account': 'IBKR', 'currency': None, 'setup_metadata': metadata,
                          'expected': json_value(result), 'effects': after})
    (output / 'investment-import.json').write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    target_engine.dispose()
    engine.dispose()
    print(len(cases), 'real import results and complete financial table states')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url', required=True)
    parser.add_argument('--oracle-database-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
