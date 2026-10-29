"""Generate synthetic cash-source canonical byte vectors without database or network access."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import random

from ft.domain.canonical import CanonicalSerializationError, canonical_bytes

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'compose/backend/src/test/resources/python-cash-canonical.json'


def content():
    inputs = []
    for text in ('-0.00', '0E+19', '-0E+19', '0E+20', '0E-18', '0E-19',
        '1.000000000000000000', '1.0000000000000000000', '1E+19', '1E+20',
        'NaN', 'sNaN', 'Infinity', '-Infinity', '0E+999999999999999999'):
        inputs.append(dict(kind='decimal', value=text))
    for value in (None, True, False, 0, -(2**100), 2**100,
        {'z': 'anonymous', 'a': [True, None, 123]},
        {'\U00010000': 1, '\ue000': 2},
        ''.join(chr(i) for i in range(32))+'"\\\x7f\u2028\U0001f680',
        [1.0], {'nested': {'number': 1.5}}):
        inputs.append(dict(kind='json', value=value))
    for value in ('1.0', '-0.0', '1e-300', 'nan', 'inf', '-inf'):
        inputs.append(dict(kind='float', value=value))
    rng = random.Random(581319)
    for _ in range(1000):
        digits = ''.join(str(rng.randrange(10)) for _ in range(rng.randint(1, 45)))
        inputs.append(dict(kind='decimal', value=rng.choice(('', '-'))+digits+'E'+str(rng.randint(-25, 25))))
    for _ in range(500):
        value = {'second': [rng.randint(-(2**80), 2**80), rng.choice((None, True, False)),
            ''.join(chr(rng.choice((34, 92, 10, 127, 0x4e2d, 0xe000, 0x10000, 0x1f680))) for _ in range(8))],
            'first': {'amount': rng.choice(('0.00', '-10.0100', '1E-18'))}}
        inputs.append(dict(kind='json', value=value))
    for year in (1, 1970, 2026, 9999):
        for microsecond in (0, 1, 123456, 999999):
            inputs.append(dict(kind='timestamp', value=datetime(year, 12, 28, 23, 59, 59,
                microsecond, tzinfo=timezone.utc).isoformat()))
    rows = []
    for item in inputs:
        value = item['value']
        if item['kind'] == 'decimal': value = Decimal(value)
        elif item['kind'] == 'float': value = float(value)
        elif item['kind'] == 'timestamp': value = datetime.fromisoformat(value)
        row = dict(input=item)
        try:
            encoded = canonical_bytes(value)
            row.update(text=encoded.decode(), sha256=hashlib.sha256(encoded).hexdigest())
        except CanonicalSerializationError as failure:
            row['error'] = str(failure)
        rows.append(row)
    return json.dumps(rows, ensure_ascii=True, separators=(',', ':'))+'\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = content()
    if args.check:
        if TARGET.read_text() != result: raise RuntimeError('Cash canonical oracle is stale')
    else: TARGET.write_text(result)
    print(f'{len(json.loads(result))} cash canonical vectors '+('verified' if args.check else 'generated'))


if __name__ == '__main__': main()
