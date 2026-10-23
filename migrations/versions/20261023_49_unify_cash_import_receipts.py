"""Move real legacy import results into user-scoped receipts, then retire their table."""
from datetime import datetime
from hashlib import sha256
import json
import re

from alembic import op
import sqlalchemy as sa


revision = "20261023_49"
down_revision = "20261021_48"
branch_labels = None
depends_on = None
COUNTERS = {"new_rows", "updated_rows", "mapping_saved", "skipped_rows"}
MAX_SUMMARY_BYTES = 1024 * 1024
BATCH_SIZE = 125


def _text(value, maximum):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError("legacy_receipt_invalid")
    value.encode("utf-8")
    return value


def _summary(value):
    if not isinstance(value, dict):
        raise ValueError("legacy_receipt_invalid")
    counts = {key: value[key] for key in COUNTERS if key in value}
    if any(type(count) is not int or count < 0 for count in counts.values()):
        raise ValueError("legacy_receipt_invalid")
    result = {"committed": True, "summary": counts}
    if "by_account" in value:
        accounts = value["by_account"]
        if (not isinstance(accounts, dict) or any(not isinstance(name, str)
            or type(count) is not int or count < 0 for name, count in accounts.items())):
            raise ValueError("legacy_receipt_invalid")
        for name in accounts:
            name.encode("utf-8")
        result["legacy_by_account"] = accounts
    if len(json.dumps(result, allow_nan=False).encode()) > MAX_SUMMARY_BYTES:
        raise ValueError("legacy_receipt_invalid")
    return result


def _receipt(row):
    identifier = _text(row["id"], 36)
    if re.fullmatch(r"[A-Za-z0-9_:.~-]{1,36}", identifier) is None:
        raise ValueError("legacy_receipt_invalid")
    workspace = _text(row["workspace_id"], 64)
    user = _text(row["user_id"], 36)
    if re.fullmatch(r"[A-Za-z0-9_:.~-]{1,36}", user) is None:
        raise ValueError("legacy_receipt_invalid")
    key = _text(row["idempotency_key"], 255)
    if not isinstance(row["created_at"], datetime):
        raise ValueError("legacy_receipt_invalid")
    identity = ["legacy-cash-import-receipt-v1", identifier, workspace, user, key]
    return {"id": identifier, "workspace_id": workspace, "user_id": user,
        "idempotency_key": key, "request_digest": sha256(json.dumps(identity,
            ensure_ascii=False, separators=(",", ":")).encode()).hexdigest(),
        "operation_kind": "legacy_import", "plan_id": None, "session_digest": None,
        "edit_context_digest": None, "result_summary": _summary(row["result_json"]),
        "created_at": row["created_at"]}


def _batches(connection, table):
    after = None
    while True:
        query = sa.select(table).order_by(table.c.id).limit(BATCH_SIZE)
        if after is not None:
            query = query.where(table.c.id > after)
        rows = connection.execute(query).mappings().all()
        if not rows:
            return
        yield rows
        after = rows[-1]["id"]


def upgrade():
    connection = op.get_bind()
    old = sa.Table("cash_import_commits", sa.MetaData(), autoload_with=connection, resolve_fks=False)
    current = sa.Table("cash_mutation_receipts", sa.MetaData(), autoload_with=connection, resolve_fks=False)
    # Finish every bounded preflight before copying or dropping any source row.
    try:
        for rows in _batches(connection, old):
            values = [_receipt(row) for row in rows]
            identities = [(row["workspace_id"], row["user_id"], row["idempotency_key"]) for row in values]
            if connection.execute(sa.select(current.c.id).where(sa.or_(
                current.c.id.in_([row["id"] for row in values]),
                sa.tuple_(current.c.workspace_id, current.c.user_id, current.c.idempotency_key).in_(identities),
            )).limit(1)).first() is not None:
                raise ValueError("legacy_receipt_collision")
    except (TypeError, ValueError, RecursionError) as error:
        code = "legacy_receipt_collision" if error.args == ("legacy_receipt_collision",) else "legacy_receipt_invalid"
        raise ValueError(code) from None
    for rows in _batches(connection, old):
        connection.execute(current.insert(), [_receipt(row) for row in rows])
    op.drop_table("cash_import_commits")


def downgrade():
    connection = op.get_bind()
    if connection.execute(sa.text("SELECT 1 FROM cash_mutation_receipts WHERE operation_kind='legacy_import' LIMIT 1")).first():
        raise ValueError("legacy import results exist; refusing a lossy schema downgrade")
    # Only an empty historical table is recoverable: discarded technical JSON is not recreated.
    op.create_table("cash_import_commits",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.String(36), nullable=False),
        sa.Column("idempotency_key", sa.String(255), nullable=False),
        sa.Column("session_digest", sa.String(64), nullable=False),
        sa.Column("result_json", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workspace_id", "idempotency_key", name="uq_cash_import_commits_workspace_key"))
    op.create_index("ix_cash_import_commits_workspace", "cash_import_commits", ["workspace_id"])
