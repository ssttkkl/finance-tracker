"""Stable application semantics for local or remote change sets."""
from ft.domain.application import ChangeSetStatusDTO, OperationResult


class ChangeSetService:
    def __init__(self, repository):
        self._repository = repository

    def status(self) -> ChangeSetStatusDTO:
        changed = tuple(self._repository.status())
        return ChangeSetStatusDTO(changed_files=changed, clean=not changed)

    def commit(self, message=None) -> OperationResult:
        committed = self._repository.commit(message)
        return OperationResult(
            ok=True,
            count=1 if committed else 0,
            details={"committed": committed},
        )

    def reset(self) -> OperationResult:
        changed = tuple(self._repository.reset())
        return OperationResult(ok=True, count=len(changed), details={"files": changed})
