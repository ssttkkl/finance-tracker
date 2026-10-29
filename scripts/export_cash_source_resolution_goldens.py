"""Generate anonymous source-choice contracts without databases or statement files."""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace

from ft.application.cash_import_operations import _wire
from ft.application.cash_source_issue_resolutions import _pending_patch, prepare_source_issue_resolutions


TARGET = Path(__file__).resolve().parents[1] / 'compose/backend/src/test/resources/python-cash-source-resolutions.json'


def cases():
    fields = [None, {}, [], [1], True, 1, 'text', {'note': '来源备注'}, {'note': None}, {'note': ''},
        {'source_payload': None}, {'source_payload': {}}, {'source_payload': {'x': [1, True, 1.5, None]}},
        {'_source_payload_present': True}, {'_source_payload_present': False}, {'_source_payload_present': None},
        {'_source_payload_present': 1}, {'_source_payload_present': 'true'}, {'_source_explanation': None, 'note': 'x'},
        {'_source_explanation': {}, 'note': 'x'}, {'_source_explanation': [], 'note': 'x'}, {'workspace_id': 'other'},
        {'account_name': 'other'}, {'record_id': 'other'}, {'note': '\u007f\n𐀀\ue000'}, {'amount': '-20.00'},
        {'amount': '-21'}, {'amount': -20}, {'amount': -20.0}, {'amount': 'NaN'}, {'amount': None},
        {'components': []}, {'components': [{}]}, {'components': [{'component_id': 'foreign', 'ordinal': 0}]},
        {'components': [{'component_id': '501', 'ordinal': 0}]}, {'components': [{'ordinal': 0}, {'ordinal': 0}]},
        {'components': [{'ordinal': 2147483648}]}, {'components': [{'ordinal': True}]},
        {'components': [{'ordinal': 0, 'label': 'extra'}]}, {'category_id': 'synthetic-category'},
        {'currency': 'USD'}, {'currency': 'cny'}, {'account_id': '42'}, {'account_id': True},
        {'record_type': 'transfer_out', 'record_subtype': 'ordinary_transfer'},
        {'occurred_at': '2026-08-01T08:00:00+08:00'}, {'occurred_at': '2026-08-02T00:00:00Z'},
        {'source_payload': {'\ue000': 0.0, '𐀀': 1e-7, 'large': 2**80}}, {'counterparty_account_attrs': [1]},
    ]
    payloads = [None, {}, {'备注': '来源'}, {'cells': [True, False, None, 0.5]}, [], True, 'body']
    for pending in fields:
        for payload in payloads:
            yield {'mode': 'pending', 'issue': {'pending_fields': pending, 'pending_source_payload': payload}}
    base = dict(state='posted', record_origin='import', amount='-20.00', currency='CNY', occurred_at='2026-08-01T00:00:00Z',
        account_id='41', record_type='consumption', record_subtype='not_applicable', cash_granularity='atomic', note='原备注',
        source_payload={'备注': '原备注'}, components=[dict(component_id='501', account_id='41', amount='-20.00', currency='CNY', ordinal=0)])
    selected = dict(issue_id='1', expected_issue_revision=2, resolution='apply_source')
    issue = dict(id=1, revision=2, code='source_update_pending', field_path=None, pending_fields={}, pending_source_payload=None,
        origin={'𐀀': 1e-7, '\ue000': 0.0, 'boolean': True, 'text': '\u007f\n'})
    for state, origin in [('posted', 'import'), ('posted', 'manual'), ('draft', 'import')]:
        for pending in fields:
            for protected in [[], ['note', 'source_payload']]:
                yield dict(mode='prepare', current={**base, 'state': state, 'record_origin': origin},
                    issues=[{**issue, 'pending_fields': pending}], choices=[selected], patch={}, protected=protected)
    for body in payloads:
        for present in [None, True, False, 'true']:
            pending = {'note': 'valid', **({'_source_payload_present': present} if present is not None else {})}
            yield dict(mode='prepare', current=base, issues=[{**issue, 'pending_fields': pending, 'pending_source_payload': body}],
                choices=[selected], patch={}, protected=[])
    for code in ['source_update_pending', 'source_identity_ambiguous', 'posting_incomplete']:
        for state in ['draft', 'posted']:
            for resolution in ['apply_source', 'keep_existing', 'create_independent', 'merge_into', 'unknown']:
                yield dict(mode='prepare', current={**base, 'state': state}, issues=[{**issue, 'code': code, 'pending_fields': {'note': 'new'}}],
                    choices=[{**selected, 'resolution': resolution}], patch={}, protected=[])
    for key, values in [('note', ['a', 'b']), ('amount', ['-20.00', '-20']), ('source_payload', [{'v': 1}, {'v': True}]),
                        ('source_payload', [{'v': [1.0]}, {'v': [True]}])]:
        for patch in [{}, {key: ''}]:
            yield dict(mode='prepare', current={**base, 'state': 'draft'}, issues=[{**issue, 'id': i, 'pending_fields': {key: value}} for i, value in enumerate(values, 1)],
                choices=[{**selected, 'issue_id': str(i)} for i in [1, 2]], patch=patch, protected=[])
    for choice in [{**selected, 'issue_id': value} for value in ['2', '01', '9223372036854775808', '9999999999999999999']] + [{**selected, 'expected_issue_revision': 3}]:
        yield dict(mode='prepare', current=base, issues=[issue], choices=[choice], patch={}, protected=[])


def content():
    result = []
    for case in cases():
        before = deepcopy(case)
        try:
            if case['mode'] == 'pending':
                value = _pending_patch(SimpleNamespace(**case['issue']))
            else:
                patch, resolutions = prepare_source_issue_resolutions(case['current'],
                    [SimpleNamespace(**issue) for issue in case['issues']], case['choices'], case['patch'], protected_fields=set(case['protected']))
                value = {'patch': _wire(patch), 'resolutions': resolutions}
            expected = {'value': value}
        except ValueError as error:
            expected = {'error': getattr(error, 'code', str(error)), 'fields': list(getattr(error, 'fields', ()))}
        assert case == before
        result.append({'input': before, **expected})
    return json.dumps(result, ensure_ascii=True, separators=(',', ':')) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    value = content()
    if args.check:
        if TARGET.read_text() != value:
            raise RuntimeError('Source-choice contracts are stale')
    else:
        TARGET.write_text(value)
    print(f'{len(json.loads(value))} source-choice contracts ' + ('verified' if args.check else 'generated'))


if __name__ == '__main__':
    main()
