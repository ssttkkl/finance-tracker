"""Verification and projection rebuild orchestration."""
from ft.domain.verification import VerificationResultDTO


class VerificationService:
    def __init__(self, repository, change_sets=None):
        self._repository = repository
        self._change_sets = change_sets

    def verify(self, *, fix=False) -> VerificationResultDTO:
        if fix:
            self._repository.rebuild()
            if self._change_sets is not None:
                self._change_sets.stage()
        cashflow_count, cashflow_findings = self._repository.verify_cashflows()
        investment_findings = self._repository.verify_investments()
        findings = (*cashflow_findings, *investment_findings)
        ok = not any(finding.severity == "error" for finding in findings)
        return VerificationResultDTO(
            ok=ok,
            rebuilt=fix,
            cashflow_count=cashflow_count,
            cashflow_findings=tuple(cashflow_findings),
            investment_findings=tuple(investment_findings),
        )
