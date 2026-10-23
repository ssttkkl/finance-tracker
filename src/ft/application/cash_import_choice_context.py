"""文件变化后的选择重验证明；保留失效决定，不包含可执行计划或来源正文。"""
from copy import deepcopy
import re


CONTEXT_KEY = "relation_choice_context"


class CashImportChoiceContextError(ValueError):
    pass


def _reference(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_:.~-]{1,255}", value) is not None


def _members(value):
    return isinstance(value, list) and 0 < len(value) <= 100_000 and all(
        isinstance(item, dict) and len(item) == 1 and set(item) <= {"row_ref", "transaction_id"}
        and all(_reference(reference) for reference in item.values()) for item in value)


def _validated(value):
    try:
        if (not isinstance(value, dict) or set(value) != {"version", "operations", "relation_groups",
            "invalidated_relation_choices", "relation_choice_generations"} or type(value["version"]) is not int
            or value["version"] != 1):
            raise ValueError
        operations, groups = value["operations"], value["relation_groups"]
        if not isinstance(operations, list) or len(operations) > 100_000 or not isinstance(groups, list) or len(groups) > 100_000:
            raise ValueError
        for operation in operations:
            if (not isinstance(operation, dict) or set(operation) != {"occurrence_key", "values"}
                or not _reference(operation["occurrence_key"]) or not isinstance(operation["values"], dict)
                or set(operation["values"]) != {"record_id"} or not _reference(operation["values"]["record_id"])):
                raise ValueError
        seen = set()
        for group in groups:
            if (not isinstance(group, dict) or set(group) != {"suggestion_id", "source_suggestion_id", "choice_generation", "member_refs", "candidates"}
                or not _reference(group["suggestion_id"]) or group["suggestion_id"] in seen
                or not _reference(group["source_suggestion_id"]) or not _members(group["member_refs"])
                or type(group["choice_generation"]) is not int or not 0 <= group["choice_generation"] < 2 ** 63
                or not isinstance(group["candidates"], list) or len(group["candidates"]) > 100_000
                or any(not isinstance(candidate, dict) or set(candidate) != {"candidate_id"}
                    or not _reference(candidate["candidate_id"]) for candidate in group["candidates"])):
                raise ValueError
            seen.add(group["suggestion_id"])
        stale, generations = value["invalidated_relation_choices"], value["relation_choice_generations"]
        if not isinstance(stale, dict) or len(stale) > 100_000 or not isinstance(generations, dict) or len(generations) > 100_000:
            raise ValueError
        for identifier, item in stale.items():
            if (not _reference(identifier) or not isinstance(item, dict) or set(item) != {"choice", "member_refs"}
                or not _members(item["member_refs"]) or not isinstance(item["choice"], dict)):
                raise ValueError
            choice = item["choice"]
            if (not {"suggestion_id", "action"} <= choice.keys() or set(choice) - {"suggestion_id", "action", "candidate_id", "common_fields"}
                or choice["suggestion_id"] != identifier or choice["action"] not in {"accept", "defer", "ignore"}
                or choice.get("candidate_id") is not None and not _reference(choice["candidate_id"])):
                raise ValueError
            if 'common_fields' in choice:
                from ft.domain.cash_record_policy import COMMON_CASH_FIELDS, prepare_cash_record_update
                common = choice['common_fields']
                if choice['action'] != 'accept' or not isinstance(common, dict) or common.keys() - COMMON_CASH_FIELDS:
                    raise ValueError
                prepare_cash_record_update({'state': 'draft'}, common)
        if any(not _reference(key) or type(generation) is not int or not 0 <= generation < 2 ** 63
            for key, generation in generations.items()):
            raise ValueError
        if any(group["choice_generation"] != generations.get(group["source_suggestion_id"], 0) for group in groups):
            raise ValueError
    except (ValueError, TypeError, KeyError):
        raise CashImportChoiceContextError("choice_context_invalid") from None
    return value


def _from_plan(plan):
    return _validated({"version": 1,
        "operations": [{"occurrence_key": item["occurrence_key"], "values": {"record_id": item["values"]["record_id"]}}
            for item in plan.get("operations", []) if item.get("occurrence_key") and not item.get("transaction_id")],
        "relation_groups": [{"suggestion_id": group["suggestion_id"],
            "source_suggestion_id": group.get("source_suggestion_id", group["suggestion_id"]),
            "choice_generation": group.get("choice_generation", 0),
            "member_refs": deepcopy(group["member_refs"]),
            "candidates": [{"candidate_id": candidate["candidate_id"]} for candidate in group["candidates"]]}
            for group in plan.get("relation_groups", [])],
        "invalidated_relation_choices": deepcopy(plan.get("invalidated_relation_choices", {})),
        "relation_choice_generations": deepcopy(plan.get("relation_choice_generations", {}))})


def retained_choice_context(current):
    data = dict(current.context_data)
    if current.plan is not None:
        data[CONTEXT_KEY] = _from_plan(current.plan)
    elif CONTEXT_KEY in data:
        _validated(data[CONTEXT_KEY])
    return data


def previous_choice_context(current):
    if CONTEXT_KEY not in current.context_data:
        return None
    return _validated(current.context_data[CONTEXT_KEY])


def without_choice_context(current):
    return {key: value for key, value in current.context_data.items() if key != CONTEXT_KEY}
