"""按精确来源组读取及发布当前账户映射。"""
from sqlalchemy import select, tuple_

from ft.adapters.relational.models import StatementAccountMappingModel


FUNDING_MAPPING_KIND = "funding_group_v1"


def _batches(values, size=200):
    values = sorted(set(values))
    for offset in range(0, len(values), size):
        yield values[offset:offset + size]


class CashFundingMappingRepository:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def lookup(self, identities):
        result = {}
        for batch in _batches(identities):
            rows = self.session.scalars(select(StatementAccountMappingModel).where(
                StatementAccountMappingModel.workspace_id == self.workspace_id,
                tuple_(StatementAccountMappingModel.source_type, StatementAccountMappingModel.identity_kind,
                    StatementAccountMappingModel.source_account_key).in_(batch)))
            for row in rows:
                result[(row.source_type, row.identity_kind, row.source_account_key)] = row
        return result

    def publish(self, choices, *, user_id):
        selected = {}
        for choice in choices:
            key = (choice["source_type"], FUNDING_MAPPING_KIND, choice["source_account_ref"])
            account_id = int(choice["account_id"])
            if key in selected and selected[key] != account_id:
                raise ValueError("funding_mapping_conflict")
            selected[key] = account_id
        existing = self.lookup(selected)
        for key, account_id in sorted(selected.items()):
            row = existing.get(key)
            if row is None:
                self.session.add(StatementAccountMappingModel(workspace_id=self.workspace_id,
                    source_type=key[0], identity_kind=key[1], source_account_key=key[2],
                    account_id=account_id, confirmed_by=user_id, revision=1))
            elif row.account_id != account_id:
                row.account_id, row.confirmed_by = account_id, user_id
                row.revision += 1
        if selected:
            self.session.flush()
