"""复制普通 JSON 容器；遇到其他类型时整体使用标准深复制。"""
from copy import deepcopy
from decimal import Decimal


_SCALARS = frozenset({str, int, bool, float, Decimal, type(None)})


class _RequiresDeepCopy(Exception):
    pass


def copy_value(value):
    if type(value) in _SCALARS:
        return value
    try:
        return _copy_value(value, {}, [])
    except _RequiresDeepCopy:
        pass
    return deepcopy(value)


def _copy_value(value, memo, originals):
    kind = type(value)
    if kind in _SCALARS:
        return value
    if kind not in (dict, list):
        raise _RequiresDeepCopy
    identifier = id(value)
    if identifier in memo:
        return memo[identifier]
    originals.append(value)
    result = {} if kind is dict else []
    memo[identifier] = result
    if kind is dict:
        for key, item in value.items():
            result[key if type(key) in _SCALARS else _copy_value(key, memo, originals)] = (
                item if type(item) in _SCALARS else _copy_value(item, memo, originals))
    else:
        result.extend(item if type(item) in _SCALARS else _copy_value(item, memo, originals) for item in value)
    return result
