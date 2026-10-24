"""仅公开会话任务所需字段，不公开解析正文或内部来源证明。"""
from copy import deepcopy

from ft.application.cash_import_issues import cash_import_issue


def cash_import_file_view(file):
    rows = file.document.rows if file.document is not None else ()
    codes = list(dict.fromkeys(code for row in rows if row.disposition != "skipped"
        for code in (*row.issue_codes, *((row.error_code,) if row.error_code else ()))))
    if file.error_code:
        codes.insert(0, file.error_code)
    status = "password_required" if file.status == "needs_password" else "failed" if file.status != "parsed" else "partial" if codes else "ready"
    return {"file_ref": file.file_ref, "client_file_ref": file.client_file_ref,
        "display_name": file.filename, "source_type": file.source_type, "source_scope_label": file.source_scope_label,
        "status": status, "recognized_row_count": file.document.observed_count if file.document is not None else None,
        "issues": [cash_import_issue(code, blocking_scope="file") for code in codes[:16]],
        "can_retry": file.status != "parsed"}


def cash_import_session_view(current, groups, suggestions):
    files = [cash_import_file_view(file) for file in current.files]
    inputs = {key: deepcopy(value) for key, value in current.preview_input.items()
        if key in {"account_selections", "row_choices", "relation_choices", "entry_choices"}}
    selected = {item["source_account_ref"]: item for item in inputs.get("account_selections", [])}
    return {"session_id": current.session_id, "session_revision": current.revision,
        "expires_at": current.expires_at.isoformat(), "state": "cancelled" if current.cancelled else "open",
        "files": files, "source_accounts": [{"source_account_ref": group.source_account_ref,
            "source_type": group.source_type, "display_name": group.display_name, "currencies": list(group.currencies),
            "row_count": len(group.row_refs), "suggestion": suggestions[group.source_account_ref],
            "selection": {key: value for key, value in selected[group.source_account_ref].items()
                if key in {"account_id", "expected_account_revision"}} if group.source_account_ref in selected else None}
            for group in groups], "last_preview_input": inputs or None,
        "last_plan_id": current.plan["plan_id"] if current.plan is not None else None,
        "can_preview": not current.cancelled and any(file.document is not None for file in current.files), "receipt_id": None}
