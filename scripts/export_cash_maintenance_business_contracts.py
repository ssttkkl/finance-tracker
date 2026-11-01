"""Prepare original general maintenance business-plan checkpoints."""
from __future__ import annotations

import ast
import inspect
import itertools

import export_current_cash_maintenance_contracts as contracts
import tests.contract.test_cash_mutation_business_plan as business


def scenarios():
    result = {}
    for name, method in vars(business).items():
        if not name.startswith("test_") or "context_runtime" not in inspect.signature(method).parameters:
            continue
        count = sum(isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "_preview"
            for node in ast.walk(ast.parse(inspect.getsource(method))))
        choices = []
        marks = [mark for mark in getattr(method, "pytestmark", ()) if mark.name == "parametrize"]
        for mark in marks:
            keys = [key.strip() for key in mark.args[0].split(",")]
            choices.append([dict(zip(keys, value if len(keys) > 1 else (value,))) for value in mark.args[1]])
        for index, groups in enumerate(itertools.product(*choices) if choices else [()]):
            params = {key: value for group in groups for key, value in group.items()}
            for checkpoint in range(1, max(1, count) + 1):
                key = name.removeprefix("test_") + (f"_p{index}" if marks else "") + f"_s{checkpoint}"
                result[key] = {"module": "business", "method": name, "params": params, "checkpoint": checkpoint}
    return result


SCENARIOS = scenarios()

if __name__ == "__main__":
    contracts.MODULES["business"] = business
    contracts.SCENARIOS = SCENARIOS
    contracts.main()
