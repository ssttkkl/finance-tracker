"""Export anonymous original credential-loader results and file effects."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
from ft.credentials import load_exchange_credentials, load_polymarket_credentials


def run(output):
    cases = []
    values = ['null', 'false', 'true', '0', '-0', '1', '0.0', '-0.0', '1.25', '1e3', '1.0e+3',
              '1.0e3', '.inf', '-.inf', '.nan', 'yes', 'no', 'on', 'off', '012', '0x10', '1:20',
              '[]', '{}', '[1, true, null, "secret"]', '{x: 1, y: false}', '""', '" hello "',
              '2026-01-02', '2026-01-02T03:04:05.123456Z', '!!binary c2VjcmV0', '!!set {x: null}',
              'y', 'n', 'Y', 'N', '1e+3', '-0x10', '0o12', '2026-02-30', '2026-01-02T03:04:05-00:00',
              '2026-01-02T03:04:05+25:00', '2026-01-02T03:04:05+01:90', '!!binary /w==', '&self [*self]', '!!binary ""',
              '[2026-01-02]', '[2026-01-02T03:04:05.123456Z]', '[2026-01-02T03:04:05+01:30]',
              '!!pairs [{x: 1}]', '!!omap [{x: 1}]', '{1: first, true: last}',
              '!!binary SYNTHETIC_SECRET!', '!!int SYNTHETIC_SECRET', '!!float SYNTHETIC_SECRET',
              '{1: first, true: second, 1: last}', '{a: first, b: second, a: last}', '{[1, 2]: value}',
              '!!timestamp [1, 2]', '!!float [1, 2]', '!!str [secret]', '!!null [secret]', '!!set [secret]',
              '0000-01-02', '!!set {1: null, true: null}', '!!pairs scalar', '!!omap [{x: 1, y: 2}]',
              '!!binary ab', '!!bool SYNTHETIC_SECRET', '!!bool 1', '!!timestamp SYNTHETIC_SECRET',
              '!!binary c2VjcmV0====', '!!binary Zg===', '!!binary Zg==trailing', '!!binary "Y=WJj"',
              '!!binary Zg=', '!!binary ab=c', '!!binary ab==c', '!!binary ===YWJj']
    inputs = [(None, 'binance'), ('', 'binance'), (':::bad', 'binance'), ('{bad: [unclosed', 'binance'),
              ('binance: !!python/object:secret.Type {}', 'binance'), ('[]', 'binance'),
              ('other_provider: {api_key: x}', 'binance')]
    for value in values:
        inputs += [(f'binance: {{api_key: {value}, api_secret: synthetic}}', 'binance'),
                   (f'okx: {{api_key: synthetic, api_secret: synthetic, password: {value}}}', 'okx')]
    for section in ['null', 'false', '1', '[]', '[secret]', '{}', '{api_key: synthetic}', '{api_secret: synthetic}']:
        inputs.append((f'binance: {section}', 'binance'))
    inputs += [('base: &base {api_key: first, api_secret: secret}\nbinance: {<<: *base, api_key: last}', 'binance'),
               ('binance: {api_key: first, api_key: last, api_secret: secret}', 'binance')]
    for wallet in ['', 'wallet: invalid', 'other: x', "wallet: ' 0xABCDEFabcdefabcdefabcdefabcdefabcdefabcd '",
                   "proxy_wallet: '0x1234567890abcdef1234567890abcdef12345678'", 'wallet: false, proxy_wallet: false']:
        inputs.append(('polymarket: {' + wallet + '}', 'polymarket'))
    for text, provider in inputs:
        with tempfile.TemporaryDirectory(prefix='ft-credentials-oracle-') as name, patch.dict(os.environ, {'FT_CREDENTIALS_DIR': name}):
            directory = Path(name)
            if text is not None:
                (directory / 'credentials.yaml').write_text(text, encoding='utf-8')
            try:
                expected = {'value': load_polymarket_credentials() if provider == 'polymarket' else load_exchange_credentials(provider)}
            except ValueError as error:
                expected = {'error': str(error).replace(name, '<DIR>')}
            cases.append(dict(text=text, provider=provider, expected=expected,
                              gitignore=(directory / '.gitignore').read_text() if (directory / '.gitignore').exists() else None))
    output.write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    print(len(cases), 'original credential results')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    run(parser.parse_args().output)
