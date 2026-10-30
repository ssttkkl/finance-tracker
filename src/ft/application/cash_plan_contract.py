"""Shared version of cash plans and their retained decision references."""

CASH_PLAN_CONTRACT_VERSION = 2


def current_cash_plan_contract(plan):
    return (isinstance(plan, dict) and type(plan.get("contract_version")) is int
        and plan["contract_version"] == CASH_PLAN_CONTRACT_VERSION)
