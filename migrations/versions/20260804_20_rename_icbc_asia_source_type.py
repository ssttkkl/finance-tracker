"""Rename the ICBC Asia cash import channel.

Revision ID: 20260804_20
Revises: 20260804_19
Create Date: 2026-08-04
"""
from __future__ import annotations

from collections.abc import Iterable

import sqlalchemy as sa
from alembic import op


revision = "20260804_20"
down_revision = "20260804_19"
branch_labels = None
depends_on = None

_OLD_SOURCE = "icbc_asia_current_account"
_NEW_SOURCE = "icbc_asia"


def _renamed_record_id(record_id: str, *, old_source: str, new_source: str) -> str:
    old_prefix = f"{old_source}_"
    if record_id.startswith(old_prefix):
        return f"{new_source}_{record_id.removeprefix(old_prefix)}"
    return record_id


def _assert_active_identities_do_not_conflict(
    rows: Iterable[dict[str, object]], *, old_source: str, new_source: str,
) -> None:
    bind = op.get_bind()
    proposed: set[tuple[str, str]] = set()
    for row in rows:
        workspace_id = str(row["workspace_id"])
        record_id = _renamed_record_id(
            str(row["record_id"]), old_source=old_source, new_source=new_source,
        )
        identity = (workspace_id, record_id)
        if identity in proposed:
            raise RuntimeError("工银亚洲渠道迁移后会产生重复的活跃业务行")
        proposed.add(identity)
        conflict = bind.execute(sa.text(
            """
            SELECT 1
            FROM cash_transactions
            WHERE workspace_id = :workspace_id
              AND source_type = :source_type
              AND record_id = :record_id
              AND deleted_at IS NULL
            LIMIT 1
            """
        ), {
            "workspace_id": workspace_id,
            "source_type": new_source,
            "record_id": record_id,
        }).scalar()
        if conflict is not None:
            raise RuntimeError("工银亚洲渠道迁移后会与现有活跃业务行冲突")


def _rename_source_type(*, old_source: str, new_source: str) -> None:
    bind = op.get_bind()
    active_rows = list(bind.execute(sa.text(
        """
        SELECT id, workspace_id, record_id
        FROM cash_transactions
        WHERE source_type = :source_type AND deleted_at IS NULL
        """
    ), {"source_type": old_source}).mappings())
    _assert_active_identities_do_not_conflict(
        active_rows, old_source=old_source, new_source=new_source,
    )
    all_rows = list(bind.execute(sa.text(
        """
        SELECT id, record_id
        FROM cash_transactions
        WHERE source_type = :source_type
        """
    ), {"source_type": old_source}).mappings())
    for row in all_rows:
        bind.execute(sa.text(
            """
            UPDATE cash_transactions
            SET source_type = :new_source, record_id = :record_id
            WHERE id = :id
            """
        ), {
            "id": row["id"],
            "new_source": new_source,
            "record_id": _renamed_record_id(
                str(row["record_id"]), old_source=old_source, new_source=new_source,
            ),
        })


def upgrade() -> None:
    _rename_source_type(old_source=_OLD_SOURCE, new_source=_NEW_SOURCE)


def downgrade() -> None:
    _rename_source_type(old_source=_NEW_SOURCE, new_source=_OLD_SOURCE)
