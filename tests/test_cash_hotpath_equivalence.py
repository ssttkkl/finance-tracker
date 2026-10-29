from dataclasses import replace
from decimal import Decimal
from datetime import datetime, timezone

import pytest

from ft.domain.cash_projection import CashProjectionFact, build_cash_projections
from test_cash_source_matching import _row
from test_cash_import_funding import _file


def _fact(identifier=1, amount='-20', **changes):
    return CashProjectionFact(identifier, 101, datetime(2026, 1, 1, tzinfo=timezone.utc), Decimal(amount),
        'CNY', '', None, '', 'fixture', str(identifier), **changes)


@pytest.mark.parametrize('amount', ['-20.00', '20.00', '0', '-0.00'])
@pytest.mark.parametrize('funding', [None, 0, 12])
@pytest.mark.parametrize('attention', [False, True])
def test_single_projection_equals_general_algorithm(amount, funding, attention, monkeypatch):
    import ft.domain.cash_projection as module
    fact = _fact(amount=amount, funding_relation_id=funding, needs_attention=attention)
    expected = module._validate_group([fact.id], {fact.id: fact}, ())
    monkeypatch.setattr(module, '_validate_group', lambda *a: pytest.fail('unnecessary singleton graph'))
    assert build_cash_projections((fact,), ()).projections == (expected,)


def test_draft_sort_and_subclass_keep_general_path(monkeypatch):
    import ft.domain.cash_projection as module
    class Fact(CashProjectionFact):
        pass
    ordinary = _fact(2)
    special = Fact(**_fact(10).__dict__)
    draft = replace(_fact(3), state='draft', created_at=datetime(2026, 1, 1, tzinfo=timezone.utc))
    calls = []
    original = module._validate_group
    def count(*args):
        calls.append(args[0]); return original(*args)
    monkeypatch.setattr(module, '_validate_group', count)
    result = build_cash_projections((ordinary, special, draft), ())
    assert calls and [p.projection_id for p in result.projections] == ['cash:10', 'cash:2', 'cash:3']
    assert result.projections[-1].state == 'draft' and not result.projections[-1].statistics_eligible


def test_empty_graph_keeps_validation_without_empty_equivalence(monkeypatch):
    import ft.domain.cash_relation_graph as module
    from ft.domain.cash_relation_policy import RelationEndpoint, CashRelationError
    component = RelationEndpoint('11', '1', Decimal('-20'), 'CNY', '101', 'consumption', 'not_applicable')
    monkeypatch.setattr(module, '_Equivalence', lambda *a: pytest.fail('unnecessary empty relation graph'))
    assert module.build_cash_relation_graph((_fact(),), (component,), ()) == build_cash_projections((_fact(),), ())
    with pytest.raises(CashRelationError, match='relation_endpoint_incompatible'):
        module.build_cash_relation_graph((_fact(),), (replace(component, amount=Decimal('-10')),), ())
    with pytest.raises(CashRelationError, match='relation_endpoint_incomplete'):
        module.build_cash_relation_graph((_fact(),), (component,), (), funding_component_ids=('unknown',))


def test_provider_only_batch_does_not_compute_unused_identity_digests(monkeypatch):
    import ft.domain.cash_source_batch as module
    rows = {f'a:r{i}': _row(i, provider_id=str(i)) for i in range(1, 21)}
    monkeypatch.setattr(module, '_content_value', lambda *a: pytest.fail('unnecessary content digest'))
    result = module.resolve_source_batch(rows, ())
    assert len(result.rows) == 20 and all(item.action == 'new' and item.bind for item in result.rows)
    rows['a:r21'] = _row(21, provider_id='21', fields={'amount': True})
    assert module.resolve_source_batch(rows, ()).rows[-1].action == 'conflict'


def test_funding_reuses_equal_parts_and_identity_only_within_call(monkeypatch):
    import ft.application.cash_import_funding as module
    file = _file(methods=('余额',) * 20)
    original, digest = module._parts, module._digest
    calls, groups = [], []
    def parts(*args):
        calls.append(1); return original(*args)
    def hashed(*args):
        if args[0] == 'funding-group-v1': groups.append(1)
        return digest(*args)
    monkeypatch.setattr(module, '_parts', parts)
    monkeypatch.setattr(module, '_digest', hashed)
    assert len(module.scan_cash_funding((file,)).rows) == 20
    assert len(calls) == len(groups) == 1
    module.scan_cash_funding((file,))
    assert len(calls) == len(groups) == 2


@pytest.mark.parametrize('variant', ['normal', 'bad', 'provider', 'position', 'weak', 'file', 'old'])
def test_provider_batch_matches_general_algorithm(variant, monkeypatch):
    import ft.domain.cash_source_batch as module
    from test_cash_source_matching import _old
    rows = {f'a:r{i}': _row(i, provider_id=str(i)) for i in (1, 2)}
    records = ()
    if variant == 'bad': rows['a:r2'] = _row(2, provider_id='2', fields={'amount': True})
    if variant == 'provider': rows['a:r2'] = _row(2, provider_id='1')
    if variant == 'position': rows['a:r2'] = _row(1, provider_id='2')
    if variant == 'weak': rows['a:r2'] = _row(2)
    if variant == 'file': rows['b:r2'] = rows.pop('a:r2')
    if variant == 'old': records = (_old(),)
    fast = module.resolve_source_batch(rows, records)
    monkeypatch.setattr(module, '_new_provider_batch', lambda *a: None)
    assert fast == module.resolve_source_batch(rows, records)


def test_graph_decimal_subclass_preserves_copy_protocol():
    from ft.domain.cash_relation_graph import build_cash_relation_graph
    from ft.domain.cash_relation_policy import RelationEndpoint
    calls = []
    class Amount(Decimal):
        def copy_abs(self, *args):
            calls.append(1); return super().copy_abs(*args)
    component = RelationEndpoint('11', '1', Amount('-20'), 'CNY', '101', 'consumption', 'not_applicable')
    build_cash_relation_graph((_fact(),), (component,), ())
    assert calls


def test_wire_container_subclasses_retain_setitem_protocol():
    from ft.application.cash_import_operations import _wire_in_place
    calls = []
    class Items(dict):
        def __setitem__(self, key, value):
            calls.append(key); super().__setitem__(key, value)
    value = Items(a=1, b=None)
    assert _wire_in_place(value) is value and calls == ['a', 'b']


@pytest.mark.parametrize('field', ['row_owner', 'digest', 'parser'])
def test_funding_cache_preserves_unknown_scalar_protocol(field, monkeypatch):
    import ft.application.cash_import_funding as module
    class Text(str):
        __hash__ = None
    file = _file(methods=('余额',) * 2)
    if field == 'row_owner':
        file = replace(file, document=replace(file.document, rows=tuple(replace(row, source_account_identifier=Text('owner')) for row in file.document.rows)))
    if field == 'digest': file = replace(file, digest=Text(file.digest))
    if field == 'parser': file = replace(file, document=replace(file.document, parser_version=Text(file.document.parser_version)))
    original, calls = module._parts, []
    def count(*args): calls.append(1); return original(*args)
    monkeypatch.setattr(module, '_parts', count)
    assert len(module.scan_cash_funding((file,)).rows) == 2 and len(calls) == 2


def test_digest_bytes_and_reentrant_string_conversion():
    import json
    from hashlib import sha256
    from ft.domain.cash_source_matching import _digest
    class Nested:
        def __str__(self): return _digest('nested', {'中文': Decimal('1.20')})
    values = ({'b': [Nested(), None], 'a': Decimal('1.00')},)
    assert _digest(*values) == sha256(json.dumps(values, ensure_ascii=False, sort_keys=True, separators=(',', ':'), default=str).encode()).hexdigest()


@pytest.mark.parametrize('field', ['document_evidence', 'document_coverages'])
def test_provider_fast_path_does_not_skip_file_evidence(field):
    from ft.domain.cash_source_batch import SourceBatchInput, resolve_source_batch
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    rows = SourceBatchInput({'a:r1': _row(provider_id='1')}, **{field: (SourceDocumentEvidence('fixture-bank', 'fixture-v1', object()),)})
    with pytest.raises(AttributeError):
        resolve_source_batch(rows, ())


def test_non_document_file_evidence_preserves_fast_path(monkeypatch):
    import ft.domain.cash_source_batch as module
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    row = _row(provider_id='1')
    rows = module.SourceBatchInput({'a:r1': row}, document_evidence=(SourceDocumentEvidence(row.source_type, row.parser_version, row.coverage),),
        document_coverages=(row.coverage,))
    monkeypatch.setattr(module, '_content_value', lambda *a: pytest.fail('unnecessary content digest'))
    assert module.resolve_source_batch(rows, ()).rows[0].action == 'new'


def test_non_document_known_evidence_uses_provider_path(monkeypatch):
    import ft.domain.cash_source_batch as module
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    row = _row(provider_id='1')
    monkeypatch.setattr(module, '_index', lambda *a: pytest.fail('unnecessary index without existing rows'))
    result = module.resolve_source_batch({'a:r1': row}, (),
        known_document_coverages=(SourceDocumentEvidence(row.source_type, row.parser_version, row.coverage),))
    assert result.rows[0].action == 'new'


def test_wire_does_not_reenter_exact_empty_containers(monkeypatch):
    import ft.application.cash_import_operations as module
    original, calls = module._wire_in_place, []
    def counted(value):
        calls.append(value)
        return original(value)
    monkeypatch.setattr(module, '_wire_in_place', counted)
    empty_tuple = ()
    value = [{'dict': {}, 'list': [], 'tuple': empty_tuple} for _ in range(100)]
    result = counted(value)
    assert result is value and all(row == {'dict': {}, 'list': [], 'tuple': []} for row in result)
    assert len({id(row['tuple']) for row in result}) == 100
    assert len(calls) == 101


def test_source_rows_reuses_only_file_scope_digest_within_file(monkeypatch):
    import ft.application.cash_import_source_rows as module
    import ft.domain.cash_source_matching as matching
    original, calls = matching._digest, []
    def counted(*values):
        calls.append(values)
        return original(*values)
    monkeypatch.setattr(matching, '_digest', counted)
    file = _file(source='icbc_debit', methods=('余额',) * 20)
    rows = module.source_rows(file)
    keys = [row.file_scope_key for row in rows.values()]
    assert len(set(keys)) == 1 and len(calls) == 1
    module.source_rows(file)
    assert len(calls) == 2


def test_wire_empty_container_retains_custom_key_assignment_protocol():
    from ft.application.cash_import_operations import _wire_in_place
    calls = []
    class Key:
        def __hash__(self):
            calls.append('hash')
            return 1
    key = Key()
    value = {key: {}}
    calls.clear()
    assert _wire_in_place(value) is value
    assert calls == ['hash']


@pytest.mark.parametrize('error', [ValueError, TypeError, ArithmeticError])
def test_current_mapping_get_error_keeps_original_change_semantics(error):
    from collections import UserDict
    from ft.domain.cash_record_policy import prepare_cash_record_update
    class Current(UserDict):
        def get(self, key, default=None):
            if key == 'note':
                raise error('internal')
            return super().get(key, default)
    assert prepare_cash_record_update(Current(state='draft'), {'note': 'value'}).values == {'note': 'value'}


@pytest.mark.parametrize('source', ['wechat', 'alipay'])
def test_file_scope_reuse_preserves_digest_for_channel_scope(source):
    from ft.application.cash_import_source_rows import source_rows
    from ft.domain.cash_source_matching import _digest
    file = _file(source=source, methods=('余额',) * 3)
    file = replace(file, document=replace(file.document, rows=tuple(replace(row,
        source_account_identifier=str(index), provider_id=f'{source}-{index}')
        for index, row in enumerate(file.document.rows))))
    for occurrence in source_rows(file).values():
        identity = (occurrence.source_type, occurrence.source_account_key, occurrence.file_digest, occurrence.parser_version) if source == 'wechat' else (
            occurrence.source_type, occurrence.file_digest, occurrence.parser_version)
        assert occurrence.file_scope_key == _digest(*identity)
