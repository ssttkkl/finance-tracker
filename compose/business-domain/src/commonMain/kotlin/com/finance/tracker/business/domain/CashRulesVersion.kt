package com.finance.tracker.business.domain

/**
 * 共享领域规则的显式版本。
 *
 * 端侧派生的投影、余额与服务端校验都以此为口径；规则语义变化时必须递增，
 * 端侧据此触发整体重派生，并让快照/派生结果可观察到规则版本。
 */
const val CASH_RULES_VERSION: String = "cash-rules-v1"
