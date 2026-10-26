"""区分确定未提交与提交调用后的未知结果。"""
from contextlib import contextmanager

from ft.cash_diagnostics import cash_diagnostic_scope, report_cash_failure


class CashCommitFailure(RuntimeError):
    def __init__(self, commit_state):
        self.commit_state = commit_state
        super().__init__("commit_state_unknown" if commit_state == "unknown" else "commit_failed")


class CashCommitBoundary:
    def __init__(self):
        self.commit_state = "unknown"

    def no_prior_result(self):
        # 调用方已持有写锁，原键及上下文消费检查均成功且没有收据。
        self.commit_state = "not_committed"

    @contextmanager
    def guard(self, known_errors):
        try:
            yield
        except known_errors as error:
            if self.commit_state == "not_committed":
                error.commit_state = "not_committed"
            raise
        except Exception as error:
            failure = CashCommitFailure(self.commit_state)
            failure.cash_diagnostic = report_cash_failure(error)
            raise failure from None

    def commit(self, uow):
        # 即使提交返回异常，数据库也可能已经生效；不能以随后 rollback 推断撤销。
        self.commit_state = "unknown"
        with cash_diagnostic_scope("database_commit", scope="request"):
            uow.commit()
