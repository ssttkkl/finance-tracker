"""导入与后续维护的公开原因；不使用异常正文生成用户文案。"""
import re


_REASONS = {
    "entry_changed": ("这笔收支已变化，请刷新后重试", "open_record"),
    "common_field_conflict": ("合并信息存在不同的人工修改，请确认", "review_relation"),
    "inheritance_required": ("请选择是否保留整笔修改", "review_relation"),
    "manual_relation_forbidden": ("手工流水不能与其他流水合并", "review_relation"),
    "account_missing": ("尚未选择账户", "edit"),
    "account_unavailable": ("账户暂不可用", "edit"),
    "account_changed": ("账户信息已变化，请重新选择", "edit"),
    "account_currency_missing": ("账户尚未支持该币种", "edit"),
    "funding_unrecognized": ("尚未确认资金账户", "edit"),
    "allocation_amount_missing": ("尚未补齐各账户金额", "edit"),
    "allocation_amount_invalid": ("各账户金额需要核对", "edit"),
    "allocation_amount_mismatch": ("各账户金额与合计不符", "edit"),
    "amount_unreadable": ("金额无法识别", "edit"),
    "time_unreadable": ("交易时间无法识别", "edit"),
    "currency_unrecognized": ("币种无法识别", "edit"),
    "direction_unrecognized": ("收支方向需要补充", "edit"),
    "posting_incomplete": ("流水信息尚未补齐", "edit"),
    "incomplete_record": ("流水信息尚未补齐", "edit"),
    "allocation_missing": ("尚未补齐各账户金额", "edit"),
    "component_total_mismatch": ("各账户金额与合计不符", "edit"),
    "component_direction_mismatch": ("各账户的收支方向不一致", "edit"),
    "component_currency_mismatch": ("各账户金额的币种不一致", "edit"),
    "component_account_mismatch": ("资金账户需要核对", "edit"),
    "component_reference_invalid": ("账户分摊已变化，请重新核对", "edit"),
    "duplicate_component_ordinal": ("账户分摊重复，请重新核对", "edit"),
    "funding_unreadable": ("资金信息无法识别，已跳过", "reselect_file"),
    "category_unavailable": ("所选分类已不可用", "edit"),
    "source_update_pending": ("账单更新尚未确认，可稍后处理", "open_record"),
    "batch_choice_conflict": ("同一笔流水的修改不一致，请重新核对", "edit"),
    "row_not_selected": ("这条记录未选择导入", "none"),
    "fixed_field_conflict": ("账单与已有流水的固定信息不同，未更新", "open_record"),
    "field_not_editable": ("这项信息不能修改", "edit"),
    "invalid_field": ("流水信息需要核对", "edit"),
    "invalid_record_type": ("流水类型与业务细分不符", "edit"),
    "source_identity_ambiguous": ("暂时无法确认是否重复，可稍后处理", "open_record"),
    "source_identity_conflict": ("来源信息与已有流水不一致", "open_record"),
    "source_document_conflict": ("同一账单的页面内容不一致", "reselect_file"),
    "batch_identity_conflict": ("同一笔流水在所选账单中的内容不一致", "reselect_file"),
    "row_unreadable": ("这条记录无法识别，已跳过", "reselect_file"),
    "row_save_failed": ("这条流水未能保存，请稍后重试", "retry"),
    "record_changed": ("已有流水已变化，本次未更新", "open_record"),
    "issue_changed": ("待处理内容已变化，请刷新后重试", "open_record"),
    "issue_resolution_invalid": ("当前处理方式不可用，请刷新后重试", "open_record"),
    "source_resolution_conflict": ("待采用的信息不一致，请重新选择", "open_record"),
    "source_evidence_save_failed": ("账单信息未能保存，请稍后重试", "retry"),
    "retry_aliases_incomplete": ("相关账单已移除，请重新导入", "reselect_file"),
    "retry_context_changed": ("相关账单已变化，请重新导入", "reselect_file"),
    "relation_save_failed": ("本次未能合并，可稍后处理", "review_relation"),
    "relation_context_changed": ("相关流水已变化，请重新确认", "review_relation"),
    "relation_pending": ("待确认合并，可回主页处理", "review_relation"),
    "relation_discovery_failed": ("这笔流水暂未完成配对，可稍后处理", "review_relation"),
    "relation_endpoint_incompatible": ("这些流水暂不能合并", "review_relation"),
    "statement_source_unsupported": ("暂不支持这种账单", "reselect_file"),
    "statement_unreadable": ("这份账单无法读取，请重新选择", "reselect_file"),
    "statement_rows_too_large": ("账单内容过多，请缩小导出范围", "reselect_file"),
    "statement_file_too_large": ("文件过大，请缩小导出范围", "reselect_file"),
    "statement_input_invalid": ("这份账单内容不完整，请重新选择", "reselect_file"),
    "statement_header_not_found": ("账单格式无法识别，请重新导出", "reselect_file"),
    "statement_header_invalid": ("账单格式无法识别，请重新导出", "reselect_file"),
    "statement_encoding_unsupported": ("账单文字无法读取，请重新导出", "reselect_file"),
    "password_required": ("请填写账单密码并重新选择文件", "reselect_file"),
    "password_incorrect": ("账单密码不正确", "reselect_file"),
    "unpaid_closed": ("交易未付款，已跳过", "none"),
    "failed_repay": ("还款未成功，已跳过", "none"),
    "account_opening": ("这行是开户信息，已跳过", "none"),
}
_SCOPES = {"row", "relation_group", "file", "request"}
_ACTIONS = {"edit", "reselect_file", "open_record", "review_relation", "retry", "none"}


def cash_import_issue(code, *, field_path=None, blocking_scope="row", next_action=None, after_import_action=None):
    if (not isinstance(code, str) or re.fullmatch(r"[a-z][a-z0-9_]{0,63}", code) is None
        or not isinstance(blocking_scope, str) or blocking_scope not in _SCOPES
        or next_action is not None and (not isinstance(next_action, str) or next_action not in _ACTIONS)
        or field_path is not None and (not isinstance(field_path, str) or re.fullmatch(r"[A-Za-z0-9_.:\[\]-]{1,255}", field_path) is None)
        or after_import_action is not None and (not isinstance(after_import_action, str) or after_import_action != "open_home_item")):
        raise ValueError("cash_issue_invalid")
    message, default_action = _REASONS.get(code, ("这条记录暂时无法处理，可稍后重试", "retry"))
    if code == "fixed_field_conflict":
        label = {"amount": "金额", "currency": "币种", "occurred_at": "时间", "account_id": "账户", "components": "账户分摊"}.get(field_path)
        if label:
            message = f"已有流水的{label}与账单不同，未更新"
    result = {"code": code, "message": message, "field_path": field_path, "blocking_scope": blocking_scope,
        "next_action": next_action or default_action}
    if after_import_action:
        result["after_import_action"] = after_import_action
    return result
