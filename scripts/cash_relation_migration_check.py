"""Read-only preflight and verification; never upgrade or rebuild a ledger."""
import argparse
import importlib
import json
import os
import re
from pathlib import Path
import sys

import sqlalchemy as sa

from migrations.cash_relation_v3.audit import verify_report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('preflight', 'verify'))
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    target = args.report.expanduser().resolve()
    root = Path(__file__).resolve().parents[1]
    if target == root or root in target.parents:
        parser.error('report must be outside the repository')
    url = os.environ.get('FT_DATABASE_URL')
    if not url:
        parser.error('FT_DATABASE_URL must select an explicit database')
    engine = None
    try:
        selected = sa.engine.make_url(url)
        if selected.get_backend_name() not in {'sqlite', 'postgresql'}:
            parser.error('only SQLite and PostgreSQL are supported')
        if selected.get_backend_name() == 'sqlite':
            if not selected.database or selected.database == ':memory:' or not Path(selected.database).is_file():
                parser.error('preflight requires an existing SQLite database')
            selected = selected.set(database='file:' + str(Path(selected.database).resolve()),
                query={**selected.query, 'mode': 'ro', 'uri': 'true'})
        engine = sa.create_engine(selected)
        with engine.connect() as connection:
            if connection.dialect.name == 'sqlite':
                connection.exec_driver_sql('PRAGMA query_only=ON')
            elif connection.dialect.name == 'postgresql':
                connection.exec_driver_sql('SET TRANSACTION READ ONLY')
            else:
                parser.error('only SQLite and PostgreSQL are supported')
            if args.mode == 'preflight':
                module = importlib.import_module('migrations.versions.20261030_53_cash_relation_model')
                report = module.preflight_report(connection)
                with target.open('x', encoding='utf-8') as stream:
                    json.dump(report, stream, ensure_ascii=False, sort_keys=True, indent=2)
                result = {'preflight': True, 'workspace_count': len(report['workspaces'])}
            else:
                result = verify_report(connection, json.loads(target.read_text(encoding='utf-8')))
            connection.rollback()
        print(json.dumps(result))
        return 0
    except ValueError as error:
        parts = str(error).split(':')
        controlled = re.fullmatch(r'cash_relation_migration_[a-z_]+', parts[0]) is not None
        result = {'code': parts[0] if controlled else 'cash_relation_migration_report_invalid'}
        if controlled:
            result['references'] = [part for part in parts[1:] if re.fullmatch(r'[A-Za-z0-9_,.-]{1,128}', part)]
        print(json.dumps(result), file=sys.stderr)
        return 1
    except (sa.exc.SQLAlchemyError, OSError):
        print('cash_relation_migration_check_failed', file=sys.stderr)
        return 1
    finally:
        if engine is not None:
            engine.dispose()


if __name__ == '__main__':
    raise SystemExit(main())
