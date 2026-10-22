"""手工新建继续使用公开业务字段，并限制当次 JSON 输入。"""
from pydantic import Field

from ft.web.cash_import_protocol import StrictInput


class ManualCashComponentInput(StrictInput):
    account_name: str = ""
    account_id: str | int | None = None
    amount: str | int
    currency: str | None = None
    label: str = ""


class ManualCashRecordInput(StrictInput):
    account_name: str = ""
    amount: str | int
    currency: str
    occurred_at: str
    counterparty: str = ""
    counterparty_account: str = ""
    counterparty_account_attrs: list[str] = Field(default_factory=list)
    note: str | None = None
    category_id: str | None = None
    category: str | None = None
    record_type: str = "other"
    record_subtype: str = ""
    components: list[ManualCashComponentInput] | None = Field(default=None, max_length=64)
