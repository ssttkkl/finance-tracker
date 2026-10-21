"""来源候选只冻结一次，引用本身包含完整集合的内容签名。"""
import re

from ft.domain.cash_source_matching import _digest


class SourceCandidateSets:
    def __init__(self):
        self.values = {}
        self._groups = {}

    def reference(self, candidates, group_ref=None):
        if group_ref is not None and group_ref in self._groups:
            return self._groups[group_ref]
        key = _digest("cash-source-candidates-v1", candidates)
        if key not in self.values:
            self.values[key] = list(candidates)
        if group_ref is not None:
            self._groups[group_ref] = key
        return key

    def bind(self, value, candidates, group_ref=None):
        value["candidate_ids"] = []
        value.pop("candidate_set_ref", None)
        if candidates:
            value["candidate_set_ref"] = self.reference(candidates, group_ref)


def source_candidate_ids(value, sets):
    reference = value.get("candidate_set_ref")
    if reference is None:
        return tuple(value.get("candidate_ids", ()))
    if value.get("candidate_ids") or reference not in sets:
        raise ValueError("source_candidate_set_invalid")
    return tuple(sets[reference])


def frozen_source_candidate_sets(values):
    """每个集合只校验及恢复一次，不能把被篡改的列表当成原签名内容。"""
    if not isinstance(values, dict) or len(values) > 100_000:
        raise ValueError("source_candidate_set_invalid")
    result = {}
    for key, candidates in values.items():
        if (not isinstance(key, str) or not re.fullmatch(r"[0-9a-f]{64}", key)
            or not isinstance(candidates, list) or not 1 <= len(candidates) <= 100_000
            or any(not isinstance(identifier, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", identifier)
                or int(identifier) >= 2 ** 63 for identifier in candidates)
            or len(set(candidates)) != len(candidates) or _digest("cash-source-candidates-v1", candidates) != key):
            raise ValueError("source_candidate_set_invalid")
        result[key] = tuple(candidates)
    return result
