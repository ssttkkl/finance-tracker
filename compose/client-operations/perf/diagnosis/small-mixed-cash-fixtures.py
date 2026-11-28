import importlib.util
from pathlib import Path
import sys

fixture_code = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("large_cash_fixture", fixture_code)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
root, backend = Path(sys.argv[2]), Path(sys.argv[3])
root.mkdir()
for size in (32, 128, 512):
    def documents():
        indices = [0, *range(5, size + 5)]
        yield fixture.statement('bank', 'ccb_debit', 'xls', '900000000000001234', [fixture.bank_row(i) for i in indices])
        yield fixture.statement('alipay', 'alipay', 'csv', 'synthetic@example.test', [fixture.alipay_row(i) for i in indices])
        tail = range(size + 5 - size // 4, size + 7)
        yield fixture.statement('alipay-overlap', 'alipay', 'csv', 'synthetic@example.test', [fixture.alipay_row(i) for i in tail])
    fixture.documents = documents
    fixture.generate(root / str(size), backend)
