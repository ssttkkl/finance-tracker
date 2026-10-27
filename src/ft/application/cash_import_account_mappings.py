"""导入账户的只读建议和成功使用证明发布；普通流水维护不修改默认。"""
import re

from sqlalchemy import select, tuple_

from ft.adapters.relational.cash_funding_mappings import (
    FUNDING_MAPPING_KIND, CashFundingMappingRepository, _batches,
)
from ft.adapters.relational.models import AccountAliasModel, AccountModel
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE


def _legacy_key(group):
    if group.source_type == "wechat" and group.origin_key == WECHAT_DEFAULT_SCOPE and group.identity_kind == "payment_method":
        return group.source_type, "payment_method", group.identity_key
    if group.identity_kind == "file_account":
        if group.source_type in {"icbc_credit", "icbc_debit"}:
            return group.source_type, "file_account", group.identity_key
        if group.source_type == "icbc_asia":
            return group.source_type, "account_identifier", group.identity_key
    return None


class CashImportAccountMappings:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id
        self.repository = CashFundingMappingRepository(session, workspace_id)

    def suggest(self, groups):
        groups = tuple(groups)
        keys = {group.source_account_ref: (group.source_type, FUNDING_MAPPING_KIND, group.source_account_ref)
            for group in groups}
        legacy = {group.source_account_ref: _legacy_key(group) for group in groups}
        mappings = self.repository.lookup([*keys.values(), *(key for key in legacy.values() if key)])
        alias_groups = {group.source_account_ref: ("account_identifier", group.identity_key)
            for group in groups if group.identity_kind == "file_account"
            and re.fullmatch(r"[0-9]{5,}", group.identity_key)}
        aliases = {}
        for batch in _batches(alias_groups.values()):
            for alias in self.session.scalars(select(AccountAliasModel).where(
                AccountAliasModel.workspace_id == self.workspace_id,
                tuple_(AccountAliasModel.alias_type, AccountAliasModel.alias_value).in_(batch))):
                aliases.setdefault((alias.alias_type, alias.alias_value), set()).add(alias.account_id)
        accounts = {}
        ids = {row.account_id for row in mappings.values() if row.account_id is not None}
        ids.update(identifier for values in aliases.values() for identifier in values)
        for batch in _batches(ids):
            accounts.update((row.id, row) for row in self.session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self.workspace_id, AccountModel.id.in_(batch))))
        result = {}
        for group in groups:
            ref = group.source_account_ref
            current, old = mappings.get(keys[ref]), mappings.get(legacy[ref])
            mapping = current if current is not None else old
            basis = "confirmed_mapping" if current is not None else "legacy_mapping"
            candidates = {mapping.account_id} if mapping is not None else aliases.get(alias_groups.get(ref), set())
            valid = [accounts[identifier] for identifier in candidates if identifier in accounts
                and accounts[identifier].active and accounts[identifier].type in {"cash", "loan", "lend"}]
            account = valid[0] if len(valid) == 1 else None
            result[ref] = {"account_id": str(account.id) if account is not None else None,
                "expected_account_revision": account.revision if account is not None else None,
                "basis": (basis if mapping is not None else "account_alias") if account is not None else "none"}
        return result

    def publish(self, choices, *, user_id):
        self.repository.publish(choices, user_id=user_id)
