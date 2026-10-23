"""为直接构造当前读模型的测试补齐稳定收支对象。"""
from uuid import uuid4
from sqlalchemy import select
from ft.adapters.relational.cash_entries import public_values
from ft.adapters.relational.models import CashEntryModel, CashEntryMemberModel, CashProjectionModel, CashProjectionMemberModel, CashTransactionModel


def seed_cash_entries(session, workspace_id):
    sources = {row.id: row for row in session.scalars(select(CashTransactionModel).where(
        CashTransactionModel.workspace_id == workspace_id, CashTransactionModel.deleted_at.is_(None)))}
    covered = set(session.scalars(select(CashEntryMemberModel.transaction_id).where(CashEntryMemberModel.workspace_id == workspace_id)))
    groups = []
    for projection in session.scalars(select(CashProjectionModel).where(CashProjectionModel.workspace_id == workspace_id)):
        group = set(session.scalars(select(CashProjectionMemberModel.cash_transaction_id).where(CashProjectionMemberModel.projection_row_id == projection.id)))
        if group and not group & covered:
            groups.append((projection.root_cash_transaction_id, group, projection))
            covered.update(group)
    groups.extend((identifier, {identifier}, None) for identifier in sources.keys() - covered)
    entries, members = [], []
    for root, group, projection in groups:
        source = sources[root]
        values = public_values(source)
        if projection:
            values.update({field: getattr(projection, field) for field in ('note', 'category_id', 'counterparty')})
        entry = CashEntryModel(id=str(uuid4()), workspace_id=workspace_id, root_transaction_id=root, revision=1,
            **values, manual_fields=sorted(set(source.manual_overrides or {}) & values.keys()))
        entries.append(entry)
        if projection:
            projection.entry_id = entry.id
        members.extend(CashEntryMemberModel(workspace_id=workspace_id, entry_id=entry.id, transaction_id=identifier,
            standalone_values=public_values(sources[identifier]), standalone_manual_fields=[]) for identifier in group)
    session.add_all(entries)
    session.flush(entries)
    session.add_all(members)
    session.flush()


def create_import_record(service, payload):
    """直接构造有来源证明的测试流水，不借用手工创建入口。"""
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.record_type import default_cash_record_subtype
    with service._uow as uow:
        account = service._account(uow._state().session, payload['account_name'])
        record_type = payload.get('record_type', 'other')
        subtype = payload.get('record_subtype')
        if subtype in (None, '', 'not_applicable'):
            subtype = default_cash_record_subtype(record_type)
        row = {**payload, 'source_type': 'fixture', 'record_id': str(uuid4()), 'record_origin': 'import',
            'record_subtype': subtype}
        model, account = uow.cashflows.add(account.type, row, account=account, return_model=True)
        service._snapshot_delta(uow, None, uow.cashflows._to_row(model, account))
        CashProjectionService.maintain_standalone_fact_if_ready_in_session(uow._state().session, uow.workspace_id, model)
        result = service._record_detail_in_uow(uow, model.id)
        uow.commit()
        return result
