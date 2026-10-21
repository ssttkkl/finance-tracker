"""私有失败证明的完整形状校验；读正文之前失败关闭。"""
from dataclasses import fields
import re

from ft.application.cash_import_plans import _reference
from ft.application.cash_import_staging import ImportSessionStorageUnavailable
from ft.application.cash_source_candidate_sets import frozen_source_candidate_sets
from ft.domain.cash_source_matching import SourceCoverage


_HEX = re.compile(r"[0-9a-f]{64}")
_SESSION = re.compile(r"[A-Za-z0-9_-]{43}")


def _hash(value):
    return isinstance(value, str) and _HEX.fullmatch(value) is not None


def _identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[1-9][0-9]{0,18}", value) is not None and int(value) < 2 ** 63


def _unique(values, predicate, maximum=100_000, *, nonempty=False):
    return (isinstance(values, list) and int(nonempty) <= len(values) <= maximum
        and all(predicate(value) for value in values) and len(set(values)) == len(values))


def _text(value, maximum=64):
    return isinstance(value, str) and 1 <= len(value) <= maximum


def _witness(value):
    if (not isinstance(value, dict) or set(value) != {"source_type", "parser_version", "coverage"}
        or not isinstance(value["source_type"], str) or not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", value["source_type"])
        or not _text(value["parser_version"])):
        return False
    coverage = value["coverage"]
    if (not isinstance(coverage, dict) or set(coverage) != {field.name for field in fields(SourceCoverage)}
        or any(coverage[name] is not None and not _text(coverage[name]) for name in ("start", "end"))
        or any(type(coverage[name]) is not bool for name in ("complete", "start_inclusive", "end_inclusive"))
        or not isinstance(coverage["evidence"], str) or len(coverage["evidence"]) > 255
        or not isinstance(coverage["filters"], dict)
        or any(not _text(coverage[name]) for name in ("time_precision", "strategy_version"))
        or not _hash(coverage["document_key"])):
        return False
    SourceCoverage(**coverage)
    return True


def _resolution(value, candidate_sets):
    required = {"action", "transaction_id", "candidate_ids", "reason", "bind"}
    if (not isinstance(value, dict) or not required <= value.keys() or set(value) - required - {"candidate_set_ref"}
        or not isinstance(value["action"], str) or value["action"] not in {"new", "match", "duplicate", "pending", "conflict"}
        or value["transaction_id"] is not None and not _identifier(value["transaction_id"])
        or not _unique(value["candidate_ids"], _identifier)
        or not isinstance(value["reason"], str) or value["reason"] and not re.fullmatch(r"[a-z][a-z0-9_]{0,63}", value["reason"])
        or type(value["bind"]) is not bool):
        return False
    if "candidate_set_ref" in value and (not _hash(value["candidate_set_ref"])
        or value["candidate_set_ref"] not in candidate_sets or value["candidate_ids"]):
        return False
    return value["action"] != "match" or value["transaction_id"] is not None


def retry_data(value):
    """只接收服务端生成的有界资料，不修补损坏的来源决定。"""
    try:
        if (not isinstance(value, dict) or type(value.get("version")) is not int or value["version"] != 1
            or not isinstance(value["units"], dict) or len(value["units"]) > 100_000
            or not _unique(value["derived_context_ids"], lambda item: isinstance(item, str) and _SESSION.fullmatch(item), 256)):
            raise ValueError
        for name in ("root_context_id",):
            if name in value and (not isinstance(value[name], str) or not _SESSION.fullmatch(value[name])):
                raise ValueError
        for name in ("root_receipt_id", "parent_receipt_id"):
            if name in value and not _reference(value[name], 36):
                raise ValueError
        if "derivation_digest" in value and not _hash(value["derivation_digest"]):
            raise ValueError
        if "derivation_key" in value and not _reference(value["derivation_key"]):
            raise ValueError
        proofs = value["source_proofs"]
        if (not isinstance(proofs, dict) or set(proofs) != {"version", "groups", "document_witnesses", "candidate_sets"}
            or type(proofs["version"]) is not int or proofs["version"] != 1
            or not isinstance(proofs["groups"], dict) or len(proofs["groups"]) > 100_000
            or not isinstance(proofs["document_witnesses"], list) or len(proofs["document_witnesses"]) > 20
            or not all(_witness(item) for item in proofs["document_witnesses"])):
            raise ValueError
        frozen_source_candidate_sets(proofs["candidate_sets"])
        for key, proof in proofs["groups"].items():
            if (not _hash(key) or not isinstance(proof, dict)
                or set(proof) != {"records", "documents", "allowed_inputs", "credit_units"}
                or any(not isinstance(proof[name], dict) or len(proof[name]) > 100_000 for name in proof)
                or any(not _identifier(identifier) or not _hash(signature) for identifier, signature in proof["records"].items())
                or any(not _hash(scope) or signature is not None and not _hash(signature) for scope, signature in proof["documents"].items())
                or any(not _reference(ref) or not _hash(signature) for ref, signature in proof["allowed_inputs"].items())):
                raise ValueError
            for root, descriptor in proof["credit_units"].items():
                if (not _reference(root) or not isinstance(descriptor, dict) or set(descriptor) != {"row_refs", "binding_keys"}
                    or not _unique(descriptor["row_refs"], _reference, nonempty=True)
                    or not set(descriptor["row_refs"]) <= proof["allowed_inputs"].keys()
                    or not _unique(descriptor["binding_keys"], _hash, maximum=600_000, nonempty=True)):
                    raise ValueError
        seen = set()
        for root, unit in value["units"].items():
            if (not _reference(root) or not isinstance(unit, dict)
                or set(unit) != {"row_refs", "source_ref", "proof_key", "validated", "resolution"}
                or type(unit["validated"]) is not bool or not _hash(unit["proof_key"])
                or unit["proof_key"] not in proofs["groups"]
                or not _unique(unit["row_refs"], _reference, nonempty=True)
                or seen.intersection(unit["row_refs"]) or unit["source_ref"] not in unit["row_refs"]
                or not set(unit["row_refs"]) <= proofs["groups"][unit["proof_key"]]["allowed_inputs"].keys()
                or not _resolution(unit["resolution"], proofs["candidate_sets"])):
                raise ValueError
            if unit["validated"] and unit["resolution"]["action"] == "new":
                if proofs["groups"][unit["proof_key"]]["credit_units"].get(root, {}).get("row_refs") != unit["row_refs"]:
                    raise ValueError
            seen.update(unit["row_refs"])
        return value
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        raise ImportSessionStorageUnavailable from None
