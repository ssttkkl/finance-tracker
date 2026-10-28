from copy import deepcopy
from datetime import datetime, timezone, timedelta
from decimal import Decimal

import pytest

from ft.application.cash_import_posting_validation import PostingValidationCache
from ft.domain.cash_record_policy import CashRecordPolicyError, validate_cash_record_for_posting
from test_cash_record_policy import _record


def _outcome(validate, record):
    try:
        validate(record)
    except CashRecordPolicyError as error:
        return error.code, error.fields
    return None


def test_identical_plain_financial_values_are_validated_once_per_call():
    calls = []
    def validate(value):
        calls.append(1)
        validate_cash_record_for_posting(value)
    cached = PostingValidationCache(validate)
    record = _record()
    for index in range(20):
        cached({**deepcopy(record), 'note': str(index)})
    assert calls == [1]
    PostingValidationCache(validate)(record)
    assert calls == [1, 1]


@pytest.mark.parametrize('changes', [
    {}, {'amount': '-21'}, {'amount': True}, {'amount': Decimal('NaN')},
    {'amount': Decimal('1e40')}, {'amount': Decimal('1e-19')}, {'currency': 'usd'},
    {'occurred_at': None}, {'record_type': 'refund'}, {'record_subtype': 'invalid'},
    {'account_id': '42'}, {'cash_granularity': 'aggregate'}, {'components': []},
    {'components': [{'amount': '-20', 'currency': 'CNY', 'account_id': '41', 'ordinal': True}]},
    {'components': [{'amount': '-21', 'currency': 'CNY', 'account_id': '41', 'ordinal': 0}]},
])
def test_cache_matches_complete_validator_after_success(changes):
    cached = PostingValidationCache(validate_cash_record_for_posting)
    cached(_record())
    record = _record(**changes)
    assert _outcome(cached, record) == _outcome(validate_cash_record_for_posting, record)


def test_mutations_missing_fields_types_and_subclasses_do_not_reuse_success():
    calls = []
    def validate(record):
        calls.append(1)
        return validate_cash_record_for_posting(record)
    cache = PostingValidationCache(validate)
    record = _record()
    cache(record)
    record['components'][0]['amount'] = '-21'
    assert _outcome(cache, record)[0] == 'component_total_mismatch'
    record['components'][0]['amount'] = '-20'
    del record['components'][0]['ordinal']
    assert _outcome(cache, record)[0] == 'incomplete_record'
    class Text(str):
        pass
    for _ in range(2):
        cache(_record(currency=Text('CNY')))
        cache(_record(occurred_at=datetime(2026, 1, 1, tzinfo=timezone(timedelta(hours=8)))))
    assert len(calls) == 7


def test_unknown_component_copy_protocol_and_failed_calls_are_not_cached():
    calls = []
    class Value:
        def __deepcopy__(self, memo):
            calls.append('copy')
            return self
    cache = PostingValidationCache(validate_cash_record_for_posting)
    record = _record()
    record['components'][0]['extra'] = Value()
    cache(record)
    cache(record)
    assert calls == ['copy', 'copy']
    invalid = _record(amount=True)
    assert _outcome(cache, invalid) == _outcome(cache, invalid) == ('invalid_field', ('amount',))


def test_changed_decimal_context_does_not_reuse_success():
    from decimal import localcontext, Overflow
    cache = PostingValidationCache(validate_cash_record_for_posting)
    record = _record(amount='-200', components=[{'account_id': '41', 'amount': '-200', 'currency': 'CNY', 'ordinal': 0}])
    cache(record)
    with localcontext() as arithmetic:
        arithmetic.Emax = 1
        arithmetic.traps[Overflow] = True
        with pytest.raises(Overflow):
            cache(record)
        with pytest.raises(Overflow):
            validate_cash_record_for_posting(record)


def test_cache_is_bounded_without_discarding_validation():
    calls = []
    def validate(record):
        calls.append(1)
        return validate_cash_record_for_posting(record)
    cache = PostingValidationCache(validate)
    for index in range(257):
        cache(_record(occurred_at=f'2026-08-01T00:00:{index % 60:02d}+00:00',
            components=[{'component_id': str(index + 1), 'account_id': '41', 'amount': '-20', 'currency': 'CNY', 'ordinal': 0}]))
    assert len(cache._successful) == 256 and len(calls) == 257
