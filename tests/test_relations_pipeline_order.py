"""Pipeline order and diamond context tests (008)."""
from decimal import Decimal

from ft.domain.relations import FactView, match_diamond_bank_refunds, pipeline
from ft.domain.relations.core.types import MatchContext
from ft.domain.relations.pipeline import run_relation_phases


def test_pipeline_run_relation_phases_exists():
    assert hasattr(pipeline, "run_relation_phases")
    assert callable(run_relation_phases)


def _fv(**kwargs):
    base = dict(
        id="x",
        amount=Decimal("10.00"),
        currency="CNY",
        account_id="a1",
        counterparty="cp",
        note="desc",
        bill_source="icbc_debit",
        source="icbc_debit",
        occurred_at="2024-01-02T12:00:00+00:00",
        fact_type="cash",
        deleted=False,
        account_type="cash",
        record_id="",
    )
    base.update(kwargs)
    return FactView(**base)


def test_diamond_without_mirror_edges_yields_empty():
    bank_ref = _fv(id="br1", amount=Decimal("10.00"), note="消费退货")
    props = match_diamond_bank_refunds(
        [bank_ref],
        accepted_mirrors=[],
        accepted_platform_refunds=[],
    )
    assert props == []


def test_run_relation_phases_returns_list():
    facts = [
        _fv(
            id="e1",
            amount=Decimal("-10.00"),
            note="消费",
            bill_source="alipay",
            source="alipay",
            account_id="same",
        ),
        _fv(
            id="b1",
            amount=Decimal("-10.00"),
            note="支付宝",
            bill_source="icbc_debit",
            source="icbc_debit",
            account_id="same",
            occurred_at="2024-01-02T12:00:05+00:00",
        ),
    ]
    out = run_relation_phases(facts, ctx=MatchContext(workspace_id="t"))
    assert isinstance(out, list)


def test_relation_service_imports_pipeline_surface():
    import inspect
    from ft.application import relations as app_rel

    src = inspect.getsource(app_rel)
    assert "MatchContext" in src
    assert "run_relation_phases" in src
    assert "match_payment_mirrors_greedy" not in src
