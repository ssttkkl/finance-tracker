package com.finance.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

private enum class ImportStage { SELECT, MAPPING, PREVIEW, RELATIONS, SUCCESS }

private data class PickedImportFile(
    val file: PlatformFile,
    val identity: ImportFileIdentity,
)

private data class ImportAccountDraft(
    val accountId: Long? = null,
    val newAccount: ImportNewAccountDto? = null,
)

private data class ImportRelationDraft(
    val kind: String,
    val status: String,
    val secondary: ImportRelationRecordDto?,
    val restoreStatus: String? = null,
    val restoreSecondary: ImportRelationRecordDto? = null,
)

private class PlatformImportFileSource(private val file: PlatformFile) : FileSource {
    override val name: String get() = file.name
    override val mediaType: String? get() = file.mimeType()?.toString()
    override val size: Long? get() = file.size().takeIf { it >= 0 }
    override suspend fun read(): ByteArray = file.readBytes()
}

@Composable
internal fun CashImportScreen(
    api: FinanceApiClient,
    sizeClass: WindowSizeClass,
    canWrite: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var stage by remember { mutableStateOf(ImportStage.SELECT) }
    var selectedFiles by remember { mutableStateOf<List<PickedImportFile>>(emptyList()) }
    var scan by remember { mutableStateOf<ImportScanDto?>(null) }
    var accountDrafts by remember { mutableStateOf<Map<String, ImportAccountDraft>>(emptyMap()) }
    var preview by remember { mutableStateOf<ImportPreviewDto?>(null) }
    var allocationDrafts by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var previewFilter by remember { mutableStateOf("all") }
    var relationDrafts by remember { mutableStateOf<Map<String, ImportRelationDraft>>(emptyMap()) }
    var relationFilter by remember { mutableStateOf("all") }
    var result by remember { mutableStateOf<ImportCommitResultDto?>(null) }
    var passwords by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var importToken by remember { mutableStateOf<String?>(null) }
    var idempotencyKey by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun clearProgress() {
        scan = null
        accountDrafts = emptyMap()
        preview = null
        allocationDrafts = emptyMap()
        previewFilter = "all"
        relationDrafts = emptyMap()
        relationFilter = "all"
        result = null
        passwords = emptyMap()
        importToken = null
        idempotencyKey = null
        error = null
        stage = ImportStage.SELECT
    }

    suspend fun receiveFiles(files: List<PlatformFile>?) {
        if (files.isNullOrEmpty()) return
        val accepted = mutableListOf<PickedImportFile>()
        val identityCandidates = mutableListOf<ImportFileIdentity>()
        var selectionError: String? = null
        for (file in files) {
            val reportedSize = file.size()
            val bytes = if (reportedSize < 0) file.readBytes() else null
            val size = if (reportedSize < 0) bytes!!.size.toLong() else reportedSize
            val validation = validateImportFile(file.name, size)
            if (validation != ImportFileValidation.Valid) {
                selectionError = importFileValidationMessage(validation)
                continue
            }
            val digest = sha1Hex(bytes ?: file.readBytes())
            val identity = ImportFileIdentity(file.name, digest, size)
            identityCandidates += identity
            accepted += PickedImportFile(file, identity)
        }
        val existing = selectedFiles.map(PickedImportFile::identity)
        val selection = addImportFiles(existing, identityCandidates)
        val newDigests = selection.files.drop(existing.size).map(ImportFileIdentity::digest).toSet()
        selectedFiles = (selectedFiles + accepted.filter { it.identity.digest in newDigests })
        if (selection.files.size != existing.size) clearProgress()
        error = selectionError ?: selection.rejections.firstOrNull()?.let(::importFileValidationMessage)
    }

    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("csv", "xls", "xlsx", "pdf")),
        mode = FileKitMode.Multiple(maxItems = MAX_IMPORT_FILES),
        onError = { failure -> error = failure.message?.takeIf(String::isNotBlank) ?: "无法打开文件选择器。" },
        onResult = { files -> scope.launch { receiveFiles(files) } },
    )

    fun mappingPayload(): List<ImportMappingDecisionDto> {
        val decisions = scan?.groups.orEmpty().map { group ->
            val draft = accountDrafts[group.groupId]
            ImportMappingDecisionDto(
                groupId = group.groupId,
                accountId = if (draft?.newAccount == null) draft?.accountId else null,
                mappingRevision = group.suggestion.mappingRevision,
                newAccount = draft?.newAccount,
            )
        }.toMutableList()
        val allocations = allocationDrafts.filterValues { it.size > 1 }.mapValues { (_, amounts) -> amounts.map(::ImportAmountDto) }
        if (allocations.isNotEmpty() && decisions.isNotEmpty()) decisions[0] = decisions[0].copy(componentAllocations = allocations)
        return decisions
    }

    fun mappingComplete(): Boolean = scan?.groups?.isNotEmpty() == true && scan!!.groups.all { group ->
        val draft = accountDrafts[group.groupId]
        draft?.accountId != null || (draft?.newAccount?.name?.trim()?.isNotEmpty() == true)
    }

    fun resetDerivedImportState() {
        preview = null
        allocationDrafts = emptyMap()
        previewFilter = "all"
        relationDrafts = emptyMap()
        relationFilter = "all"
    }

    fun returnToPasswordEntry(failure: ApiFailure?): Boolean {
        val message = when (failure?.code) {
            "import_password_required" -> "请输入账单密码。"
            "import_password_invalid" -> "账单密码错误，请重试。"
            else -> return false
        }
        val current = scan ?: return false
        val files = current.files.ifEmpty {
            selectedFiles.mapIndexed { index, picked ->
                ImportFileScanDto(
                    index = index,
                    name = picked.file.name,
                    filename = picked.file.name,
                    digest = picked.identity.digest,
                    size = picked.identity.size,
                    status = "password_required",
                    errorCode = "password_invalid",
                )
            }
        }.map { it.copy(status = "password_required", errorCode = "password_invalid") }
        scan = current.copy(ready = false, files = files)
        accountDrafts = emptyMap()
        preview = null
        allocationDrafts = emptyMap()
        previewFilter = "all"
        relationDrafts = emptyMap()
        relationFilter = "all"
        passwords = emptyMap()
        stage = ImportStage.SELECT
        error = message
        return true
    }

    fun defaultAccount(group: ImportSourceGroupDto): ImportAccountDraft {
        val name = group.displayName
        val type = if (name.contains("花呗") || name.contains("信用卡")) "loan" else "cash"
        return ImportAccountDraft(newAccount = ImportNewAccountDto("draft-${group.groupId}", name, type, group.currencies))
    }

    fun selectAccount(group: ImportSourceGroupDto, value: String) {
        accountDrafts = when {
            value == "__create__" -> accountDrafts + (group.groupId to defaultAccount(group))
            value.startsWith("__draft__") -> {
                val draftId = value.removePrefix("__draft__")
                val selected = accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).firstOrNull { it.draftId == draftId }
                    ?: return
                val merged = selected.copy(currencies = (selected.currencies + group.currencies).distinct().sorted())
                accountDrafts.mapValues { (_, draft) -> if (draft.newAccount?.draftId == draftId) draft.copy(newAccount = merged) else draft } +
                    (group.groupId to ImportAccountDraft(newAccount = merged))
            }
            value.isBlank() -> accountDrafts - group.groupId
            else -> accountDrafts + (group.groupId to ImportAccountDraft(accountId = value.toLongOrNull()))
        }
        resetDerivedImportState()
    }

    fun updateNewAccount(groupId: String, update: (ImportNewAccountDto) -> ImportNewAccountDto) {
        val existing = accountDrafts[groupId]?.newAccount ?: scan?.groups?.firstOrNull { it.groupId == groupId }?.let(::defaultAccount)?.newAccount ?: return
        val changed = update(existing)
        accountDrafts = accountDrafts.mapValues { (_, draft) ->
            if (draft.newAccount?.draftId == existing.draftId) draft.copy(newAccount = changed) else draft
        }
        resetDerivedImportState()
    }

    fun scanSelectedFiles() {
        if (selectedFiles.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null
            try {
                val value = api.scanCashImport(
                    files = selectedFiles.map { PlatformImportFileSource(it.file) },
                    passwords = passwords,
                    importToken = importToken,
                )
                scan = value
                importToken = value.importToken ?: importToken
                if (importToken != null && idempotencyKey == null) idempotencyKey = newImportIdempotencyKey()
                val ready = value.ready != false && value.files.all { it.status == "ready" }
                if (ready) {
                    accountDrafts = value.groups.associate { it.groupId to ImportAccountDraft(accountId = it.suggestion.accountId) }
                    stage = ImportStage.MAPPING
                }
            } catch (cause: Throwable) {
                val failure = cause as? ApiFailure
                importToken = failure?.importToken ?: importToken
                if (importToken != null && idempotencyKey == null) idempotencyKey = newImportIdempotencyKey()
                error = when (failure?.code) {
                    "import_password_required" -> "请输入账单密码。"
                    "import_password_invalid" -> "账单密码错误，请重试。"
                    "import_channel_unrecognized" -> "无法识别账单渠道，请重新选择文件。"
                    else -> importMappingError(failure?.code) ?: userError(cause)
                }
            } finally {
                busy = false
            }
        }
    }

    suspend fun loadPreviewRequest(nextStage: ImportStage = ImportStage.PREVIEW): Boolean {
        busy = true
        error = null
        try {
            val value = api.previewCashImport(
                importToken = importToken ?: throw IllegalStateException("import_token_missing"),
                passwords = passwords,
                mapping = mappingPayload(),
                batch = true,
            )
            preview = value
            importToken = value.importToken ?: importToken
            allocationDrafts = value.items.filter { it.components.orEmpty().size > 1 }.associate { item ->
                importItemKey(item) to item.components.orEmpty().map { it.amount?.removePrefix("+")?.removePrefix("-").orEmpty() }
            }
            relationDrafts = value.relations.associate { relation ->
                relation.id to ImportRelationDraft(
                    relation.kind,
                    if (relation.automatic) "automatic" else "pending",
                    if (relation.automatic) relation.secondary else null,
                )
            }
            relationFilter = "all"
            stage = nextStage
            return true
        } catch (cause: Throwable) {
            val failure = cause as? ApiFailure
            importToken = failure?.importToken ?: importToken
            if (returnToPasswordEntry(failure)) return false
            if (failure?.code == "import_mapping_stale") {
                scan = null
                accountDrafts = emptyMap()
                stage = ImportStage.SELECT
                error = "账户映射已变化，请重新扫描。"
            } else {
                error = importMappingError(failure?.code) ?: userError(cause)
            }
            return false
        } finally {
            busy = false
        }
    }

    fun loadPreview(nextStage: ImportStage = ImportStage.PREVIEW) {
        if (!mappingComplete() || busy) return
        scope.launch { loadPreviewRequest(nextStage) }
    }

    fun setRelationDraft(relation: ImportRelationDto, draft: ImportRelationDraft) {
        relationDrafts = relationDrafts + (relation.id to draft)
    }

    fun relationPayload(): List<JsonObject> = preview?.relations.orEmpty().mapNotNull { relation ->
        val draft = relationDrafts[relation.id] ?: ImportRelationDraft(relation.kind, if (relation.automatic) "automatic" else "pending", relation.secondary)
        val primary = relation.primary
        val payload = buildJsonObject {
            put("proposal_key", relation.id)
            put("kind", draft.kind)
            put("subtype", relation.subtype)
            put("rule_id", relation.ruleId)
            addImportEndpoint(this, primary, "primary")
            if (draft.status == "rejected") {
                put("status", "rejected")
            } else if (draft.status != "pending" && draft.secondary != null) {
                addImportEndpoint(this, draft.secondary, "secondary")
                put("status", "accepted")
            }
        }
        if (draft.status == "pending" || (draft.secondary == null && draft.status != "rejected")) null else payload
    }

    fun commitImport() {
        val current = preview ?: return
        if (busy || hasIncompleteAllocations(current, allocationDrafts) || importAllocationCount(current) > 0 || importUnsupportedCount(current) > 0) return
        val key = idempotencyKey ?: newImportIdempotencyKey().also { idempotencyKey = it }
        scope.launch {
            busy = true
            error = null
            try {
                result = api.commitCashImport(
                    importToken = importToken ?: throw IllegalStateException("import_token_missing"),
                    passwords = passwords,
                    previewDigest = current.file.digest,
                    previewRelationDigest = current.relationDigest,
                    previewChannel = current.channel,
                    relations = relationPayload(),
                    mapping = mappingPayload(),
                    idempotencyKey = key,
                    batch = true,
                )
                stage = ImportStage.SUCCESS
                onDone()
            } catch (cause: Throwable) {
                val failure = cause as? ApiFailure
                importToken = failure?.importToken ?: importToken
                val mappingError = importMappingError(failure?.code)
                if (mappingError != null) {
                    preview = null
                    relationDrafts = emptyMap()
                    stage = ImportStage.MAPPING
                    error = mappingError
                } else if (failure?.code in setOf("import_relation_reconfirmation_required", "import_relation_preview_stale", "import_relation_candidate_invalid")) {
                    if (loadPreviewRequest(ImportStage.RELATIONS)) error = "相关流水已变化，请重新确认配对。"
                } else {
                    if (returnToPasswordEntry(failure)) return@launch
                    error = when (failure?.code) {
                        "import_preview_stale" -> "文件内容已经变化，请重新选择文件。"
                        "relation_impact_required" -> "这次导入会影响已关联的流水，请先处理关联。"
                        else -> userError(cause)
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    FeaturePage("账单导入", SemanticIds.importScreen) {
        if (!canWrite) {
            StateMessage("当前工作区仅可查看。")
            TextButton(onClick = onBack) { Text("返回账本") }
        } else {
        ImportStepper(stage, sizeClass, busy, scan != null, preview != null) { target -> stage = target }
        InlineError(error)
        when (stage) {
            ImportStage.SELECT -> {
                SectionCard {
                    Text("选择账单", style = MaterialTheme.typography.titleLarge)
                    Text("支持 CSV、XLS、XLSX、PDF；每个文件不超过 100 MB。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { filePicker.launch() }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importChooseFile)) {
                            Text(if (selectedFiles.isEmpty()) "选择文件" else "添加文件")
                        }
                        Text("${selectedFiles.size}/$MAX_IMPORT_FILES")
                    }
                    if (selectedFiles.isNotEmpty()) {
                        selectedFiles.forEachIndexed { index, picked ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(picked.file.name, style = MaterialTheme.typography.titleSmall)
                                    Text(formatFileSize(picked.identity.size))
                                    scan?.files?.firstOrNull { it.index == index }?.let { status ->
                                        Text(importFileStatus(status))
                                    }
                                }
                                TextButton(onClick = {
                                    selectedFiles = selectedFiles.filterNot { it.identity.digest == picked.identity.digest }
                                    clearProgress()
                                }, enabled = !busy, modifier = Modifier.testTag("${SemanticIds.importRemoveFile}.${picked.identity.digest}")) { Text("移除") }
                            }
                        }
                    }
                    scan?.files?.filter { it.status == "password_required" }?.forEach { fileStatus ->
                        val picked = selectedFiles.getOrNull(fileStatus.index) ?: return@forEach
                        OutlinedTextField(
                            value = passwords[fileStatus.index.toString()].orEmpty(),
                            onValueChange = { passwords = passwords + (fileStatus.index.toString() to it) },
                            label = { Text("${picked.file.name}的账单密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                                .testTag("${SemanticIds.importFilePassword}.${fileStatus.index}")
                                .semantics(mergeDescendants = true) { contentDescription = "${picked.file.name}的账单密码" },
                        )
                    }
                    scan?.files?.filter { it.status == "error" }?.forEach { fileStatus ->
                        InlineError("${selectedFiles.getOrNull(fileStatus.index)?.file?.name ?: fileStatus.filename}：${importFileStatus(fileStatus)}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onBack, enabled = !busy) { Text("取消") }
                        Button(
                            onClick = { scanSelectedFiles() },
                            enabled = selectedFiles.isNotEmpty() && !busy && scan?.files?.none { it.status == "error" || (it.status == "password_required" && passwords[it.index.toString()].isNullOrBlank()) } != false,
                            modifier = Modifier.testTag(SemanticIds.importNext),
                        ) { Text(if (busy) "正在扫描…" else "扫描账单") }
                    }
                }
            }
            ImportStage.MAPPING -> scan?.let { current ->
                SectionCard {
                    Text("账户映射", style = MaterialTheme.typography.titleLarge)
                    Text("${current.channelLabel} · ${current.groups.size} 个来源账户")
                    if (current.unresolvedCount > 0) Text("有 ${current.unresolvedCount} 条流水无法准确归属，确认后会跳过。")
                    current.groups.forEach { group ->
                        HorizontalDivider()
                        val draft = accountDrafts[group.groupId]
                        val sharedDrafts = accountDrafts.values.mapNotNull(ImportAccountDraft::newAccount).distinctBy { it.draftId }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${group.maskedEvidence} · ${group.currencies.joinToString(" / ")} · ${group.rowCount} 条流水")
                            ChoicePicker(
                                "对应账户",
                                draft?.newAccount?.let { "__draft__${it.draftId}" } ?: draft?.accountId?.toString().orEmpty(),
                                listOf("" to "请选择账户") + current.accounts.map { it.id.toString() to it.name } +
                                    sharedDrafts.map { "__draft__${it.draftId}" to "新账户：${it.name}" } + ("__create__" to "创建新账户"),
                                { selectAccount(group, it) },
                                semanticId = "${SemanticIds.importMapping}.${group.groupId}",
                            )
                            draft?.newAccount?.let { account ->
                                LabeledInput(account.name, { value -> updateNewAccount(group.groupId) { it.copy(name = value.take(255)) } }, "新账户名称")
                                ChoicePicker("账户类型", account.type, listOf("cash" to "现金账户", "loan" to "贷款账户", "lend" to "借款账户"), { value -> updateNewAccount(group.groupId) { it.copy(type = value) } })
                                Text("币种：${account.currencies.joinToString(" / ")}")
                            }
                            val selectedAccount = current.accounts.firstOrNull { it.id == draft?.accountId }
                            val missingCurrencies = if (selectedAccount == null) emptyList() else group.currencies.filterNot { it in selectedAccount.currencies.orEmpty() }
                            if (missingCurrencies.isNotEmpty()) Text("将为账户新增 ${missingCurrencies.joinToString("、")}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { stage = ImportStage.SELECT }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text("上一步") }
                        Button(onClick = { loadPreview() }, enabled = mappingComplete() && !busy, modifier = Modifier.testTag(SemanticIds.importNext)) { Text(if (busy) "正在生成预览…" else "生成预览") }
                    }
                }
            }
            ImportStage.PREVIEW -> preview?.let { current ->
                SectionCard {
                    Text("核对流水", style = MaterialTheme.typography.titleLarge)
                    Text(current.channelLabel)
                    ChoicePicker("查看", previewFilter, listOf(
                        "all" to "全部 ${current.summary.total}", "new" to "待新增 ${current.summary.new}",
                        "existing" to "已存在 ${current.summary.existing}", "unresolved" to "无法识别 ${current.summary.unresolved}",
                        "requires_allocation" to "待补分配 ${incompleteAllocationCount(current, allocationDrafts)}",
                    ), { previewFilter = it })
                    val visibleItems = current.items.filter { previewFilter == "all" || it.status == previewFilter }
                    val monthGroups = importPreviewMonthGroups(visibleItems)
                    if (visibleItems.isEmpty()) Text(if (current.items.isEmpty()) "没有可核对流水。" else "没有符合条件的流水。")
                    monthGroups.forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(importPreviewMonthLabel(group.month), style = MaterialTheme.typography.titleMedium)
                            if (group.summary?.currencies.isNullOrEmpty()) {
                                Text("无收支", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        group.summary?.currencies.orEmpty().forEach { totals ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("收入 ${importPreviewSummaryAmount("income", totals.income)} ${totals.currency}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                Text("支出 ${importPreviewSummaryAmount("expense", totals.expense)} ${totals.currency}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        group.items.forEach { item ->
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("import-item-${item.recordId}")) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(importPreviewDateTimeLabel(item.occurredAt), style = MaterialTheme.typography.bodySmall)
                                    Text(importStatusLabel(item.status), style = MaterialTheme.typography.labelMedium)
                                }
                                Text(item.counterparty.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge)
                                Text(item.note.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
                                Text("${item.accountName} · ${importRecordTypeLabel(item.recordType)}", style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    val direction = importPreviewDirection(item)
                                    Text(
                                        importPreviewAmountLabel(item),
                                        color = when (direction) {
                                            "income" -> MaterialTheme.colorScheme.primary
                                            "expense" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                item.message.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (item.components.orEmpty().size > 1) {
                                    Text("组合支付分配", style = MaterialTheme.typography.labelLarge)
                                    val key = importItemKey(item)
                                    val currentAmounts = allocationDrafts[key].orEmpty()
                                    item.components.orEmpty().forEachIndexed { index, component ->
                                        LabeledInput(
                                            currentAmounts.getOrElse(index) { component.amount?.removePrefix("+")?.removePrefix("-").orEmpty() },
                                            { value -> allocationDrafts = allocationDrafts + (key to currentAmounts.toMutableList().apply { while (size <= index) add(""); this[index] = value }) },
                                            component.sourceLabel,
                                            semanticId = "${SemanticIds.importAllocation}.$key.$index",
                                        )
                                    }
                                    val balance = allocationBalance(item.amount, currentAmounts)
                                    Text(when (balance.state) {
                                        "complete" -> "已匹配 ${balance.total} ${item.currency}"
                                        "invalid" -> "金额格式无效。"
                                        else -> if (balance.difference.startsWith("-")) "超出 ${balance.difference.drop(1)} ${item.currency}" else "还差 ${balance.difference} ${item.currency}"
                                    }, color = if (balance.state == "complete") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (importUnresolvedCount(current) > 0) Text("有 ${importUnresolvedCount(current)} 条流水将跳过。")
                    if (importUnsupportedCount(current) > 0) InlineError("有流水暂不支持，请处理后再继续。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { stage = ImportStage.MAPPING }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text("上一步") }
                        Button(onClick = {
                            if (hasIncompleteAllocations(current, allocationDrafts)) {
                                error = "请补齐组合支付各组成项金额，且合计等于流水金额。"
                            } else if (current.items.any { it.components.orEmpty().size > 1 }) {
                                loadPreview(ImportStage.RELATIONS)
                            } else {
                                stage = ImportStage.RELATIONS
                            }
                        }, enabled = !busy && !hasIncompleteAllocations(current, allocationDrafts), modifier = Modifier.testTag(SemanticIds.importNext)) { Text("继续") }
                    }
                }
            }
            ImportStage.RELATIONS -> preview?.let { current ->
                SectionCard {
                    Text("确认配对", style = MaterialTheme.typography.titleLarge)
                    val automaticCount = current.relations.count { it.automatic }
                    val pendingCount = current.relations.count { relation ->
                        val draft = relationDrafts[relation.id]
                        (draft?.status ?: if (relation.automatic) "automatic" else "pending") == "pending"
                    }
                    ChoicePicker("查看", relationFilter, listOf(
                        "all" to "全部 ${current.relations.size}",
                        "automatic" to "自动 $automaticCount",
                        "pending" to "待处理 $pendingCount",
                    ), { relationFilter = it })
                    val filtered = current.relations.filter { relation ->
                        val draft = relationDrafts[relation.id]
                        relationFilter == "all" || (relationFilter == "automatic" && relation.automatic) || (relationFilter == "pending" && (draft?.status ?: "pending") == "pending")
                    }
                    if (current.relations.isEmpty()) Text("没有待确认的配对。")
                    filtered.forEach { relation ->
                        HorizontalDivider()
                        val draft = relationDrafts[relation.id] ?: ImportRelationDraft(relation.kind, if (relation.automatic) "automatic" else "pending", relation.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (draft.status == "rejected") "已拒绝" else if (relation.automatic) "自动配对" else if (draft.status == "accepted") "已确认" else "待处理"} · ${relation.label}", style = MaterialTheme.typography.titleSmall)
                            Text("${importRelationRecordLabel(relation.primary)}  与  ${draft.secondary?.let(::importRelationRecordLabel) ?: "未选择"}")
                            if (!relation.automatic && draft.status != "rejected") {
                                ChoicePicker("配对类型", draft.kind, listOf("payment_mirror" to "同笔支付", "refund_offset" to "退款冲销", "transfer_pair" to "个人转账"), { value -> setRelationDraft(relation, draft.copy(kind = value)) })
                                ChoicePicker("对侧流水", draft.secondary?.let(::importItemKey) ?: "", listOf("" to "暂不处理") + relation.candidates.map { importItemKey(it) to importRelationRecordLabel(it) }, { value ->
                                    val selected = relation.candidates.firstOrNull { importItemKey(it) == value }
                                    setRelationDraft(relation, draft.copy(status = if (selected == null) "pending" else "accepted", secondary = selected, restoreStatus = null, restoreSecondary = null))
                                })
                            }
                            TextButton(onClick = {
                                if (draft.status == "rejected") {
                                    setRelationDraft(relation, draft.copy(status = draft.restoreStatus ?: if (relation.automatic) "automatic" else "pending", secondary = draft.restoreSecondary, restoreStatus = null, restoreSecondary = null))
                                } else {
                                    setRelationDraft(relation, draft.copy(status = "rejected", restoreStatus = draft.status, restoreSecondary = draft.secondary))
                                }
                            }) { Text(if (draft.status == "rejected") "撤销拒绝" else "拒绝配对") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { stage = ImportStage.PREVIEW }, enabled = !busy, modifier = Modifier.testTag(SemanticIds.importPrevious)) { Text("上一步") }
                        Button(onClick = { commitImport() }, enabled = !busy && importUnsupportedCount(current) == 0 && importAllocationCount(current) == 0, modifier = Modifier.testTag(SemanticIds.importConfirm)) { Text(if (busy) "正在导入…" else "确认导入") }
                    }
                }
            }
            ImportStage.SUCCESS -> result?.let { committed ->
                SectionCard {
                    Text("导入完成", style = MaterialTheme.typography.titleLarge)
                    Text("新增 ${committed.newRows} 条 · 更新 ${committed.updatedRows} 条 · 已存在 ${preview?.summary?.existing ?: 0} 条 · 跳过 ${committed.skippedRows} 条")
                    Button(onClick = onBack) { Text("返回账本") }
                }
            }
        }
        }
    }
}

@Composable
private fun ImportStepper(
    stage: ImportStage,
    sizeClass: WindowSizeClass,
    busy: Boolean,
    hasScan: Boolean,
    hasPreview: Boolean,
    onSelect: (ImportStage) -> Unit,
) {
    val steps = listOf(ImportStage.SELECT to "选择账单", ImportStage.MAPPING to "账户映射", ImportStage.PREVIEW to "核对流水", ImportStage.RELATIONS to "确认配对")
    val content: @Composable () -> Unit = {
        steps.forEachIndexed { index, (step, label) ->
            val current = stage == step
            val enabled = !busy && index <= steps.indexOfFirst { it.first == stage } &&
                (step == ImportStage.SELECT || hasScan && index == 1 || hasPreview && index >= 2)
            TextButton(onClick = { onSelect(step) }, enabled = enabled, modifier = Modifier.testTag("${SemanticIds.importStepper}.$index")) {
                Text("${index + 1}. $label${if (current) " · 当前" else ""}")
            }
        }
    }
    SectionCard {
        if (sizeClass == WindowSizeClass.COMPACT) Column { content() } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { content() }
    }
}

private fun addImportEndpoint(target: kotlinx.serialization.json.JsonObjectBuilder, record: ImportRelationRecordDto, prefix: String) {
    when {
        record.factId != null -> target.put("${prefix}_fact_id", record.factId)
        record.relationRef != null -> target.put("${prefix}_record_ref", record.relationRef)
        else -> target.put("${prefix}_record_id", record.recordId)
    }
}

private fun importItemKey(item: ImportPreviewItemDto): String = item.relationRef ?: item.recordId
private fun importItemKey(item: ImportRelationRecordDto): String = item.relationRef ?: item.recordId
private fun importRelationRecordLabel(record: ImportRelationRecordDto): String =
    "${record.counterparty.ifBlank { "未填写对方" }} · ${record.amount} ${record.currency} · ${formatLocalDateTime(record.occurredAt)}"

private fun importRecordTypeLabel(value: String): String = when (value) {
    "consumption", "expense" -> "消费"
    "refund" -> "退款"
    "reversal" -> "冲正"
    "transfer_reversal" -> "转账退回"
    "withdrawal_in" -> "提现入账"
    "withdrawal_out" -> "提现"
    "transfer_in" -> "转账入账"
    "transfer_out" -> "转账转出"
    "repayment" -> "还款"
    "income" -> "收入"
    "investment_in" -> "投资转入"
    "investment_out" -> "投资转出"
    "interest" -> "利息"
    "fee" -> "费用"
    "fx_in" -> "换汇转入"
    "fx_out" -> "换汇转出"
    "bank_security_transfer" -> "银证转账"
    "merged" -> "已合并"
    else -> "其他"
}

private fun importFileStatus(file: ImportFileScanDto): String = when (file.status) {
    "ready" -> "已识别 ${file.channelLabel.orEmpty()}"
    "password_required" -> "需要账单密码"
    else -> "无法识别此文件"
}

private fun importFileValidationMessage(value: ImportFileValidation): String = when (value) {
    ImportFileValidation.UnsupportedType -> "仅支持 CSV、XLS、XLSX 或 PDF 文件。"
    ImportFileValidation.TooLarge -> "单个文件不能超过 100 MB。"
    ImportFileValidation.SizeUnavailable -> "无法读取文件大小，请重新选择。"
    ImportFileValidation.TooManyFiles -> "一次最多选择 20 个文件。"
    ImportFileValidation.Valid -> ""
}

private fun formatFileSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024L * 1024L -> "${(size + 1023) / 1024} KB"
    else -> "${(size / (1024L * 1024L))}.${((size % (1024L * 1024L)) * 10 / (1024L * 1024L))} MB"
}

private fun importStatusLabel(status: String): String = when (status) {
    "new" -> "待新增"
    "existing" -> "已存在"
    "unresolved" -> "无法识别"
    "requires_allocation" -> "待补分配"
    else -> "暂不支持"
}

private fun importAllocationCount(preview: ImportPreviewDto): Int = preview.summary.requiresAllocation
    .takeIf { it > 0 } ?: preview.items.count { it.status == "requires_allocation" }

private fun incompleteAllocationCount(preview: ImportPreviewDto, drafts: Map<String, List<String>>): Int =
    preview.items.filter { it.components.orEmpty().size > 1 }.count { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

private fun importUnresolvedCount(preview: ImportPreviewDto): Int = preview.summary.unresolved
    .takeIf { it > 0 } ?: preview.items.count { it.status == "unresolved" }

private fun importUnsupportedCount(preview: ImportPreviewDto): Int = (preview.summary.unsupported - importUnresolvedCount(preview)).coerceAtLeast(0)

private fun hasIncompleteAllocations(preview: ImportPreviewDto, drafts: Map<String, List<String>>): Boolean =
    preview.items.filter { it.components.orEmpty().size > 1 }.any { item ->
        allocationBalance(item.amount, drafts[importItemKey(item)].orEmpty()).state != "complete"
    }

private fun importMappingError(code: String?): String? = when (code) {
    "import_account_unavailable" -> "所选账户已不可用，请重新选择。"
    "import_account_name_conflict" -> "账户名称已存在，请修改后重试。"
    "import_account_draft_invalid" -> "新账户信息无效，请修改后重试。"
    "import_mapping_incomplete" -> "请为每个来源账户选择系统账户。"
    "import_composite_payment_unresolved" -> "账单包含无法准确归属的组合支付，请拆分后重试。"
    "import_component_allocation_incomplete" -> "请补齐组合支付各组成项金额。"
    "import_component_amount_invalid" -> "分摊金额格式无效，请检查后重试。"
    else -> null
}

private fun newImportIdempotencyKey(): String = "cash-import-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"
