"""调用内复用完全相同的正式财务字段校验。"""
from datetime import datetime, timezone
from decimal import Decimal, getcontext


_FIELDS = ('amount', 'currency', 'occurred_at', 'record_type', 'record_subtype',
    'account_id', 'cash_granularity', 'components')
_COMPONENT_FIELDS = ('component_id', 'account_id', 'ordinal', 'amount', 'currency')


class _Uncacheable(Exception):
    pass


def _scalar(value):
    kind = type(value)
    if value is None or kind is str or kind is int or kind is bool:
        return kind, value
    if kind is Decimal and value.is_finite():
        parts = value.as_tuple()
        if len(parts.digits) <= 38:
            return kind, parts
    if kind is datetime and value.tzinfo is timezone.utc:
        return kind, value
    raise _Uncacheable


def _key(record):
    if type(record) is not dict or any(type(key) is not str for key in record):
        raise _Uncacheable
    result = []
    for field in _FIELDS:
        value = record.get(field)
        if field == 'components':
            if type(value) is not list or len(value) > 128:
                raise _Uncacheable
            components = []
            for part in value:
                if type(part) is not dict or any(type(key) is not str or key not in _COMPONENT_FIELDS for key in part):
                    raise _Uncacheable
                components.append(tuple((key in part, _scalar(part.get(key))) for key in _COMPONENT_FIELDS))
            result.append((field in record, tuple(components)))
        else:
            result.append((field in record, _scalar(value)))
    return tuple(result)


class PostingValidationCache:
    def __init__(self, validate):
        self._validate = validate
        self._successful = set()

    def __call__(self, record):
        try:
            context = getcontext()
            arithmetic = (context.prec, context.rounding, context.Emin, context.Emax,
                context.capitals, context.clamp, tuple(context.traps.values()))
            key = arithmetic, _key(record)
        except _Uncacheable:
            return self._validate(record)
        if key in self._successful:
            return None
        result = self._validate(record)
        if len(self._successful) < 256:
            self._successful.add(key)
        return result
