from decimal import Decimal

from ft.application.relations import plan_relation_proposals
from ft.domain.relations import FactView, RelationKind, RelationStatus


def _fact(
    fact_id: str,
    *,
    amount: str,
    counterparty: str,
    record_type: str,
    occurred_at: str,
    record_id: str | None = None,
    merchant_order_id: str = "",
    txn_id: str = "",
):
    return FactView(
        id=fact_id,
        amount=Decimal(amount),
        currency="CNY",
        account_id="wechat-wallet",
        account_name="微信钱包",
        occurred_at=occurred_at,
        counterparty=counterparty,
        record_type=record_type,
        record_subtype="not_applicable",
        bill_source="wechat",
        source="wechat",
        record_id=record_id or fact_id,
        raw_payload={
            "offset_role": "refund" if record_type == "refund" and Decimal(amount) > 0 else "expense",
            "merchant_order_id": merchant_order_id,
            "txn_id": txn_id,
            "status": "退款成功" if record_type == "refund" else "支付成功",
            "txn_type": "商户退款" if record_type == "refund" else "消费",
        },
    )


def _row(fact: FactView) -> dict:
    return {
        "id": fact.id,
        "record_id": fact.record_id,
        "amount": fact.amount,
        "occurred_at": fact.occurred_at,
        "counterparty": fact.counterparty,
        "record_type": fact.record_type,
        "record_subtype": fact.record_subtype,
        "account_id": fact.account_id,
        "account_name": fact.account_name,
        "bill_source": fact.bill_source,
        "source_type": fact.bill_source,
        "source_payload": fact.raw_payload,
    }


def test_wechat_hard_refund_occupies_pair_before_same_amount_merchant_matching():
    origin = _fact(
        "wechat-origin",
        amount="-9.90",
        counterparty="瑞幸",
        record_type="consumption",
        occurred_at="2023-10-09T13:51:23+08:00",
        merchant_order_id="M-100",
        txn_id="T-100",
    )
    refund = _fact(
        "wechat-refund",
        amount="9.90",
        counterparty="瑞幸",
        record_type="refund",
        occurred_at="2023-10-09T13:51:43+08:00",
        merchant_order_id="M-100",
        txn_id="M-100",
    )
    naixue = _fact(
        "naixue",
        amount="-9.90",
        counterparty="奈雪",
        record_type="consumption",
        occurred_at="2023-10-08T18:40:36+08:00",
        merchant_order_id="N-1",
        txn_id="N-1",
    )
    duodianbao = _fact(
        "duodianbao",
        amount="-9.90",
        counterparty="多店宝网络",
        record_type="consumption",
        occurred_at="2023-10-09T20:15:11+08:00",
        merchant_order_id="D-1",
        txn_id="D-1",
    )
    facts = [origin, refund, naixue, duodianbao]

    plan = plan_relation_proposals(
        facts,
        detailed_rows=[_row(fact) for fact in facts],
        seed_ids=[fact.id for fact in facts],
    )

    refund_pairs = [
        proposal
        for proposal in plan.proposals
        if proposal.kind == RelationKind.REFUND_OFFSET.value
    ]
    assert len(refund_pairs) == 1
    assert (refund_pairs[0].primary_fact_id, refund_pairs[0].secondary_fact_id) == (
        origin.id,
        refund.id,
    )
    assert refund_pairs[0].status == RelationStatus.ACCEPTED.value
    assert refund_pairs[0].rule_id.startswith("scan.wechat")
