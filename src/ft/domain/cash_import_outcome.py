"""导入提交阶段的有界安全原因；不保存异常正文或逐项业务数据。"""

CASH_COMMIT_REASON_CODES = frozenset({
    "record_changed", "account_changed", "entry_changed", "row_save_failed",
    "source_evidence_save_failed", "relation_context_changed", "relation_save_failed",
})


def commit_failure_reason(code, fallback):
    return code if isinstance(code, str) and code in CASH_COMMIT_REASON_CODES else fallback
