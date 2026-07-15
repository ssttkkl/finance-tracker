from dataclasses import dataclass


OFFSET_FIELDS = (
    "offset_group",
    "offset_role",
    "offset_strength",
    "offset_source",
    "offset_rule_hint",
    "offset_match_type",
)


@dataclass(frozen=True)
class RefundRelation:
    refund_id: str
    expense_id: str
    strength: str
    rule_hint: str


def _action_target(value: str) -> str:
    prefix = "merge_refund_into:"
    return value[len(prefix):] if value.startswith(prefix) else ""


def _canonical_id(record_id: str, canonical_ids: dict[str, str]) -> str:
    seen = set()
    current = record_id
    while current in canonical_ids and current not in seen:
        seen.add(current)
        current = canonical_ids[current]
    return current


def _is_locked(row: dict) -> bool:
    return str(row.get("locked", "")).strip() == "1"


def _is_compatible(refund: dict, expense: dict) -> bool:
    if refund.get("category") != "income" or expense.get("category") != "expense":
        return False
    if float(refund.get("amount") or 0) <= 0 or float(expense.get("amount") or 0) >= 0:
        return False
    if refund.get("currency") != expense.get("currency"):
        return False
    if refund.get("account_name") != expense.get("account_name"):
        return False
    return True


def _relation_audit(row: dict, *, status: str, counterpart: dict | None = None,
                    rule_hint: str = "", confidence: str = "") -> dict:
    result = {
        **row,
        "reconcile_status": status,
        "match_rule": rule_hint,
        "match_confidence": confidence,
        "counterpart_record_id": "",
        "counterpart_file": "",
        "counterpart_account": "",
        "counterpart_currency": "",
        "counterpart_amount": "",
    }
    if counterpart:
        result.update({
            "counterpart_record_id": counterpart.get("record_id", ""),
            "counterpart_file": counterpart.get("_record_file", ""),
            "counterpart_account": counterpart.get("account_name", ""),
            "counterpart_currency": counterpart.get("currency", ""),
            "counterpart_amount": counterpart.get("amount", ""),
        })
    return result


def resolve_refund_relations(source_rows: list[dict], kept_rows: list[dict],
                             canonical_ids: dict[str, str], *,
                             blocked_record_ids: set[str] | None = None) -> tuple[list[RefundRelation], list[RefundRelation], list[dict]]:
    """解析退款关系并将被去重删除的关系端映射到保留记录。"""
    source_by_id = {row.get("record_id", ""): row for row in source_rows}
    kept_by_id = {row.get("record_id", ""): row for row in kept_rows}
    candidates: dict[str, list[RefundRelation]] = {}
    audit_rows = []
    blocked_record_ids = blocked_record_ids or set()

    for source_refund in source_rows:
        source_refund_id = source_refund.get("record_id", "")
        source_expense_id = _action_target(source_refund.get("proposed_action", "") or "")
        if not source_refund_id or not source_expense_id:
            continue
        refund_id = _canonical_id(source_refund_id, canonical_ids)
        expense_id = _canonical_id(source_expense_id, canonical_ids)
        refund = kept_by_id.get(refund_id)
        expense = kept_by_id.get(expense_id)
        if not refund or not expense:
            continue
        if refund_id != source_refund_id or expense_id != source_expense_id:
            refund["proposed_action"] = f"merge_refund_into:{expense_id}"
            source_expense = source_by_id.get(source_expense_id, {})
            for field in OFFSET_FIELDS:
                if not expense.get(field, ""):
                    expense[field] = source_expense.get(field, "")
            expense["offset_role"] = expense.get("offset_role", "") or "expense"
        rule_hint = source_refund.get("offset_rule_hint", "")
        relation = RefundRelation(
            refund_id=refund_id,
            expense_id=expense_id,
            strength=source_refund.get("offset_strength", "weak") or "weak",
            rule_hint=rule_hint,
        )
        candidates.setdefault(refund_id, []).append(relation)
        if refund_id != source_refund_id or expense_id != source_expense_id:
            audit_rows.append(_relation_audit(
                refund,
                status="refund_rebound_after_dedup",
                counterpart=expense,
                rule_hint=rule_hint,
                confidence=relation.strength,
            ))

    automatic = []
    pending = []
    for relations in candidates.values():
        target_ids = {relation.expense_id for relation in relations}
        if len(target_ids) != 1:
            pending.extend(relations)
            continue
        relation = relations[0]
        refund = kept_by_id[relation.refund_id]
        expense = kept_by_id[relation.expense_id]
        if (relation.refund_id in blocked_record_ids or relation.expense_id in blocked_record_ids
                or relation.strength != "strong" or _is_locked(refund) or _is_locked(expense)
                or not _is_compatible(refund, expense)):
            pending.append(relation)
            continue
        automatic.append(relation)

    refund_totals: dict[str, float] = {}
    for relation in automatic:
        refund_totals[relation.expense_id] = refund_totals.get(relation.expense_id, 0.0) + float(kept_by_id[relation.refund_id]["amount"])
    valid_automatic = []
    for relation in automatic:
        expense_amount = abs(float(kept_by_id[relation.expense_id]["amount"]))
        if refund_totals[relation.expense_id] > expense_amount + 0.005:
            pending.append(relation)
        else:
            valid_automatic.append(relation)
    return valid_automatic, pending, audit_rows


def settle_refund_relations(rows: list[dict], relations: list[RefundRelation], *, mode: str = "auto") -> tuple[list[dict], list[dict]]:
    """应用已经确认的退款关系，并返回写回行和双边审计行。"""
    if not relations:
        return rows, []
    by_id = {row.get("record_id", ""): dict(row) for row in rows}
    removed_ids = set()
    settled_expense_ids = set()
    audit_rows = []

    for relation in relations:
        refund = by_id.get(relation.refund_id)
        expense = by_id.get(relation.expense_id)
        if not refund or not expense:
            raise ValueError(f"退款关系记录不存在: {relation.refund_id} -> {relation.expense_id}")
        if _is_locked(refund) or _is_locked(expense) or not _is_compatible(refund, expense):
            raise ValueError(f"退款关系不可核销: {relation.refund_id} -> {relation.expense_id}")
        net_amount = round(float(expense["amount"]) + float(refund["amount"]), 2)
        if net_amount > 0.005:
            raise ValueError(f"退款金额超过消费金额: {relation.refund_id} -> {relation.expense_id}")

        status_kind = "full" if abs(net_amount) < 0.005 else "partial"
        status = f"refund_{status_kind}_{mode}"
        expense_before = dict(expense)
        refund_before = dict(refund)
        audit_rows.append(_relation_audit(
            expense_before,
            status=status,
            counterpart=refund_before,
            rule_hint=relation.rule_hint,
            confidence=relation.strength if mode == "auto" else "ai",
        ))
        audit_rows.append(_relation_audit(
            refund_before,
            status=status,
            counterpart=expense_before,
            rule_hint=relation.rule_hint,
            confidence=relation.strength if mode == "auto" else "ai",
        ))

        removed_ids.add(relation.refund_id)
        if status_kind == "full":
            removed_ids.add(relation.expense_id)
        else:
            expense["amount"] = str(net_amount)
            settled_expense_ids.add(relation.expense_id)

    for expense_id in settled_expense_ids:
        if expense_id in removed_ids:
            continue
        expense = by_id[expense_id]
        for field in OFFSET_FIELDS:
            expense[field] = ""
        expense["proposed_action"] = "leave_as_is"

    return [row for row in by_id.values() if row.get("record_id", "") not in removed_ids], audit_rows
