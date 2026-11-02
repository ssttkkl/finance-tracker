"""Export investment-import HTTP responses and full anonymous financial state."""
from __future__ import annotations
import argparse
import base64
from contextvars import ContextVar
import json
from pathlib import Path
from fastapi.testclient import TestClient
from export_kotlin_contracts import export
from export_investment_import_contracts import seed
from export_investment_write_contracts import state, NOW
from export_cash_transfer_contracts import clock
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


def run(target, oracle, output):
    root = Path(__file__).resolve().parents[1]
    if target == oracle or not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError('Use distinct new test databases and external output')
    export(target, output)
    export(oracle, output / 'python')
    target_engine, _ = seed(target)
    engine, sessions = seed(oracle)
    workspace, user = ContextVar('import_workspace', default=None), ContextVar('import_user', default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, mutation_service=service, operations_service=service, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases = []
    def encoded(file):
        return base64.b64encode((root / 'tests/fixtures' / file).read_bytes()).decode()
    base = {'source': 'dfzq', 'filename': 'statement.txt', 'account': '东方证券', 'content_base64': encoded('dfzq/sample_statement.txt')}
    with clock(NOW), TestClient(app, raise_server_exceptions=False) as client:
        def request(body, token='synthetic-admin', raw=None, headers=None):
            headers = {'Content-Type': 'application/json', **(headers or {})}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            before = state(sessions)
            wire = raw if raw is not None else json.dumps(body, ensure_ascii=True)
            response = client.post('/api/v1/investment-import', content=wire, headers=headers)
            after = state(sessions)
            if response.status_code >= 400:
                assert before == after, (wire, response.text)
            try:
                result = response.json()
            except ValueError:
                result = response.text
            cases.append({'raw': wire, 'headers': headers, 'status': response.status_code, 'body': result, 'effects': after})
        request(base)
        request(base)
        for source, file, account in [('ibkr', 'ibkr/transactions_1y_sample.csv', 'IBKR'), ('schwab', 'schwab/transaction_history_sample.csv', '嘉信'), ('usmart-hk', 'usmart_hk/monthly_sample.txt', '盈立证券'), ('usmart_hk', 'usmart_hk/monthly_sample.txt', '盈立证券')]:
            request({'source': source, 'filename': Path(file).name, 'account': account, 'content_base64': encoded(file)})
        for account in ['missing', 'Cash', 'Inactive', 'Crypto', 'LegacyBase']:
            request({**base, 'account': account})
        for token in [None, 'missing', 'synthetic-viewer', 'synthetic-removed', 'synthetic-empty']:
            request(base, token)
        for field, values in [('source', ['', None, 1, 'unknown', 'DFZQ', ' dfzq ']), ('account', ['', None, 1, ' 东方证券 ']),
                              ('content_base64', [None, '', 1, ' ', '!!!!', '====', 'Zg', 'Zg=', 'Zg==', 'Zg===', 'Zg==x', 'Zm9v====', 'é']),
                              ('filename', [None, '', 1, 'x.TXT', '.txt', '../x.txt', 'x.text', 'x.pdf', 'x.123456789012.txt', 'x.txt/', 'x.txt/.', 'x.txt/..', '/x.text///', 'a/.txt', 'a/..txt', 'a/...txt', 'x.txt\\', 'x.txt//.']),
                              ('currency', [None, '', 1, ' usd ', 'US', '人民币'])]:
            for value in values:
                request({**base, field: value})
        for raw in ['null', '[]', '1', '{', '{"source":"unknown","source":"dfzq"}']:
            request(None, raw=raw)
        request({**base, 'content_base64': base64.b64encode(b'').decode()})
        request(base, headers={'x-ft-statement-password': 'synthetic-password'})
        request({**base, 'content_base64': base64.b64encode('资金流水明细\n20260610\n银行转证券\nCNY'.encode()).decode()})
    (output / 'investment-import-http.json').write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    target_engine.dispose()
    engine.dispose()
    print(len(cases), 'actual HTTP and complete state cases')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url', required=True)
    parser.add_argument('--oracle-database-url', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
