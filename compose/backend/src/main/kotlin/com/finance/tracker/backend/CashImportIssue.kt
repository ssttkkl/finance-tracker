package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashImportIssueDto

internal fun cashImportIssue(code: Any?,fieldPath: Any?=null,blockingScope: Any?="row",nextAction: Any?=null,afterImportAction: Any?=null): CashImportIssueDto {
    val actions=setOf("edit","reselect_file","open_record","review_relation","retry","none")
    if(code !is String || !code.matches(Regex("[a-z][a-z0-9_]{0,63}")) || blockingScope !is String || blockingScope !in setOf("row","relation_group","file","request") ||
        nextAction!=null && (nextAction !is String || nextAction !in actions) ||
        fieldPath!=null && (fieldPath !is String || !fieldPath.matches(Regex("""[A-Za-z0-9_.:\[\]-]{1,255}"""))) ||
        afterImportAction!=null && afterImportAction!="open_home_item") throw IllegalArgumentException("cash_issue_invalid")
    val reason=cashIssueReasons[code] ?: ("这条记录暂时无法处理，可稍后重试" to "retry")
    val label=if(code=="fixed_field_conflict") mapOf("amount" to "金额","currency" to "币种","occurred_at" to "时间","account_id" to "账户","components" to "账户分摊")[fieldPath] else null
    val message=if(label==null) reason.first else "已有流水的${label}与账单不同，未更新"
    return CashImportIssueDto(code,message,fieldPath as String?,blockingScope,nextAction as String? ?: reason.second,afterImportAction as String?)
}
