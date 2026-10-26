"""现金导入故障的局部定位；异常正文、SQL 参数和局部变量不进入诊断。"""
from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
import logging
from pathlib import PurePath
import re
from uuid import uuid4


STAGES = frozenset({"request", "file_read", "row_conversion", "row_planning", "relation_discovery",
    "submit_revalidation", "source_evidence", "row_write", "batch_write", "account_mapping",
    "relation_save", "balances", "projection", "entry_edit", "database_commit"})
SCOPES = frozenset({"row", "related", "file", "request"})
POSITION_KINDS = frozenset({"csv_record", "worksheet_row", "document_row"})
MAX_LOCATIONS = 100
_LOCATION_KEYS = {"file_ref", "display_name", "row_ref", "row_number", "position_kind", "document_position", "transaction_id"}
_LOGGER = logging.getLogger("ft.cash_import.diagnostics")


def _location(value):
    result = {}
    for key in _LOCATION_KEYS:
        item = value.get(key)
        if key == "row_number":
            if type(item) is int and item > 0:
                result[key] = item
        elif key == "position_kind":
            if isinstance(item, str) and item in POSITION_KINDS:
                result[key] = item
        elif isinstance(item, str) and 0 < len(item) <= (512 if key == "display_name" else 256):
            result[key] = item
    return result


def valid_cash_diagnostic(value):
    if not isinstance(value, dict) or set(value) != {"error_id", "stage", "scope", "locations", "total_locations"}:
        return False
    locations, total = value["locations"], value["total_locations"]
    return (isinstance(value["error_id"], str) and re.fullmatch(r"[a-f0-9]{32}", value["error_id"]) is not None
        and isinstance(value["stage"], str) and value["stage"] in STAGES
        and isinstance(value["scope"], str) and value["scope"] in SCOPES
        and isinstance(locations, list) and len(locations) <= MAX_LOCATIONS
        and type(total) is int and total >= len(locations)
        and all(isinstance(item, dict) and bool(item) and _location(item) == item for item in locations))


def row_locations(refs):
    return [{"row_ref": ref} for ref in dict.fromkeys(refs)]


def operation_locations(operations):
    return row_locations(ref for operation in operations for ref in operation.get("row_refs", ()))


def parent_locations(parents, by_parent):
    result = []
    for parent in dict.fromkeys(parents):
        operation = by_parent.get(parent)
        result.extend(row_locations(operation["row_refs"]) if operation and operation.get("row_refs") else [{"transaction_id": str(parent)}])
    return result


def _enrich(locations, files):
    by_ref = {file.file_ref: file for file in files}
    result = []
    for location in locations:
        item = dict(location)
        match = re.fullmatch(r"(.+):r([0-9]+)", item.get("row_ref", ""))
        ref = item.get("file_ref") or (match.group(1) if match else None)
        file = by_ref.get(ref) if ref else next(iter(by_ref.values())) if len(by_ref) == 1 and "row_number" in item and "transaction_id" not in item else None
        if file:
            item.update(file_ref=file.file_ref, display_name=file.filename)
            if match:
                item.setdefault("row_number", int(match.group(2)))
            if "row_number" in item:
                item.setdefault("row_ref", f"{file.file_ref}:r{item['row_number']}")
                document = file.document
                if document:
                    item.setdefault("position_kind", position_kind(document.table_key))
                    if item["position_kind"] == "document_row":
                        row = next((row for row in document.rows if row.position == item["row_number"]), None)
                        if row and row.document_position:
                            item.setdefault("document_position", row.document_position)
        result.append(_location(item))
    return result


def position_kind(table_key):
    if table_key.startswith(("csv", "tsv")):
        return "csv_record"
    if table_key.startswith(("xls", "sheet")):
        return "worksheet_row"
    return "document_row"


@contextmanager
def cash_diagnostic_scope(stage=None, locations=(), *, scope=None, files=()):
    """只在失败时挂接上下文；内层位置优先，外层补充文件信息。"""
    try:
        yield
    except Exception as error:
        context = getattr(error, "_cash_diagnostic_context", None)
        if context is None:
            safe = [_location(item) for item in locations]
            safe = [item for item in safe if item]
            context = {"stage": stage or "request", "scope": scope or ("related" if safe else "request"),
                "locations": safe[:MAX_LOCATIONS], "total_locations": len(safe)}
        if files:
            context = {**context, "locations": _enrich(context["locations"], files)}
        error._cash_diagnostic_context = context
        raise


def report_cash_failure(error, *, stage="request"):
    existing = getattr(error, "cash_diagnostic", None)
    if existing is not None:
        return deepcopy(existing)
    context = getattr(error, "_cash_diagnostic_context", None) or {
        "stage": stage, "scope": "request", "locations": [], "total_locations": 0}
    diagnostic = {"error_id": uuid4().hex, **context}
    error.cash_diagnostic = diagnostic
    # 不格式化异常正文、代码行或局部变量；SQLAlchemy 异常正文含 SQL 参数。
    trace, seen, current = [], set(), error
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        frames, traceback = [], current.__traceback__
        while traceback is not None:
            code = traceback.tb_frame.f_code
            frames.append(f"{PurePath(code.co_filename).name}:{traceback.tb_lineno}:{code.co_name}")
            traceback = traceback.tb_next
        trace.append({"type": type(current).__name__, "frames": frames})
        current = current.__cause__ or current.__context__
    try:
        locations = [{key: value for key, value in item.items() if key != "display_name"}
            for item in diagnostic["locations"]]
        _LOGGER.error("cash_import_failure error_id=%s stage=%s scope=%s locations=%s total_locations=%s trace=%s",
            diagnostic["error_id"], diagnostic["stage"], diagnostic["scope"], locations, diagnostic["total_locations"], trace)
    except Exception:
        # 日志接收器故障不能覆盖原异常或改变回滚事实。
        pass
    return deepcopy(diagnostic)
