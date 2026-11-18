package com.finance.tracker.backend

/** 可预期的上传文件命令输入拒绝。 */
internal class CashImportInputError(val code: String = "import_files_invalid") : RuntimeException(code)

/** 解析文件所需的一次上传；正文已由调用方按上限单次读取。 */
internal data class CashImportUpload(
    val clientFileRef: String,
    val filename: String,
    val content: ByteArray,
    val password: String? = null,
    val sourceHint: String? = null,
    val lastModifiedAtMs: Long? = null,
)
