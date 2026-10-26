"""现金导入错误的只读诊断协议。"""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class CashDiagnosticLocationView(BaseModel):
    model_config = ConfigDict(extra="forbid")
    file_ref: str | None = None
    display_name: str | None = None
    row_ref: str | None = None
    row_number: int | None = Field(default=None, ge=1)
    position_kind: Literal["csv_record", "worksheet_row", "document_row"] | None = None
    document_position: str | None = None
    transaction_id: str | None = None


class CashDiagnosticView(BaseModel):
    model_config = ConfigDict(extra="forbid")
    error_id: str = Field(pattern=r"^[a-f0-9]{32}$")
    stage: Literal["request", "file_read", "row_conversion", "row_planning", "relation_discovery",
        "submit_revalidation", "source_evidence", "row_write", "batch_write", "account_mapping",
        "relation_save", "balances", "projection", "entry_edit", "database_commit"]
    scope: Literal["row", "related", "file", "request"]
    locations: list[CashDiagnosticLocationView] = Field(max_length=100)
    total_locations: int = Field(ge=0)
