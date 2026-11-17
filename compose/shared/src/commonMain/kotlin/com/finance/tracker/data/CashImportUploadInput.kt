package com.finance.tracker.data

import com.finance.tracker.domain.FileSource

// 使用普通类，避免默认 data class.toString 将密码或原文件字段带进诊断。
class CashImportUploadInput(
    val clientFileRef: String,
    val file: FileSource,
    val password: String? = null,
    val sourceHint: String? = null,
)
