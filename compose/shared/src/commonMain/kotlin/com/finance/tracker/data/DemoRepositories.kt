package com.finance.tracker.data

import com.finance.tracker.domain.*

private const val DEMO_WORKSPACE_ID = "demo-workspace"
private const val DEMO_USER_EMAIL = "demo@example.test"
private const val DEMO_STATEMENT_NAME = "演示银行账单.csv" // i18n-allow: demo-fixture
private const val DEMO_STATEMENT_DIGEST = "demo-statement-v1"

/** An in-memory statement fixture. Its bytes are synthetic and never come from the platform file system. */
class DemoStatementFileSource : BoundedFileSource {
    override val name: String = DEMO_STATEMENT_NAME
    override val mediaType: String = "text/csv"
    private val bytes = (
        "日期,账户,对方,金额,币种,备注\n" + // i18n-allow: demo-fixture
            "2026-10-09,日常账户,山海咖啡,-28.00,CNY,午后咖啡\n" + // i18n-allow: demo-fixture
            "2026-10-10,日常账户,示例公司,12000.00,CNY,月度收入\n" // i18n-allow: demo-fixture
        ).encodeToByteArray()
    override val size: Long = bytes.size.toLong()
    override suspend fun read(): ByteArray = bytes.copyOf()
    override suspend fun readBounded(maxBytes: Int): ByteArray {
        if (bytes.size > maxBytes) throw FileReadLimitExceeded()
        return bytes.copyOf()
    }
}

/** Session and finance data for the explicitly selected, process-local Compose demo. */
class DemoSessionRepository : SessionRepository {
    private var nextWorkspaceNumber = 2
    private var isSignedIn = true
    private var workspaces = mutableListOf(Workspace(DEMO_WORKSPACE_ID, "我的工作区", WorkspaceRole.ADMIN)) // i18n-allow: demo-fixture
    private var activeWorkspaceId: String? = DEMO_WORKSPACE_ID
    private var workspaceMembers = mutableListOf(
        WorkspaceMember("demo-user", DEMO_USER_EMAIL, WorkspaceRole.ADMIN, isSelf = true),
        WorkspaceMember("demo-editor", "teammate@example.test", WorkspaceRole.EDITOR, isSelf = false),
    )

    private fun currentSession() = Session(
        user = User(DEMO_USER_EMAIL),
        activeWorkspaceId = activeWorkspaceId,
        workspaces = workspaces.toList(),
    )

    override suspend fun hasStoredSessionToken(): Boolean = isSignedIn
    override suspend fun restoreSession(): Session = currentSession()
    override suspend fun session(): Session = currentSession()

    // The submitted form values are intentionally ignored: demo auth never persists or sends credentials.
    override suspend fun login(email: String, password: String): Session = signIn()
    override suspend fun register(email: String, password: String): Session = signIn()

    private fun signIn(): Session {
        isSignedIn = true
        if (activeWorkspaceId == null) activeWorkspaceId = workspaces.firstOrNull()?.id
        return currentSession()
    }

    override suspend fun logout() {
        isSignedIn = false
    }

    override suspend fun selectWorkspace(id: String): Session {
        if (workspaces.none { it.id == id }) throw demoFailure("workspace_not_found", 404)
        activeWorkspaceId = id
        return currentSession()
    }

    override suspend fun createWorkspace(name: String): Session {
        val workspace = Workspace("demo-workspace-${nextWorkspaceNumber++}", name, WorkspaceRole.ADMIN)
        workspaces += workspace
        activeWorkspaceId = workspace.id
        workspaceMembers = mutableListOf(WorkspaceMember("demo-user", DEMO_USER_EMAIL, WorkspaceRole.ADMIN, isSelf = true))
        return currentSession()
    }

    override suspend fun invitationPreview(token: String): InvitationPreview = InvitationPreview(
        workspace = InvitationWorkspace("协作空间"), // i18n-allow: demo-fixture
        role = WorkspaceRole.EDITOR,
        valid = token.isNotBlank(),
    )

    override suspend fun acceptInvitation(token: String): Session {
        if (token.isBlank()) throw demoFailure("invitation_invalid", 404)
        val workspace = Workspace("demo-workspace-${nextWorkspaceNumber++}", "协作空间", WorkspaceRole.EDITOR) // i18n-allow: demo-fixture
        workspaces += workspace
        activeWorkspaceId = workspace.id
        workspaceMembers = mutableListOf(WorkspaceMember("demo-user", DEMO_USER_EMAIL, WorkspaceRole.EDITOR, isSelf = true))
        isSignedIn = true
        return currentSession()
    }

    override suspend fun workspaceDetails(): WorkspaceMembers {
        val active = activeWorkspace()
        return WorkspaceMembers(WorkspaceDetails(active.id, active.name), workspaceMembers.toList())
    }

    override suspend fun updateWorkspace(name: String): Session {
        val active = activeWorkspace()
        workspaces = workspaces.map { if (it.id == active.id) it.copy(name = name) else it }.toMutableList()
        return currentSession()
    }

    override suspend fun deleteWorkspace(name: String): Session {
        val active = activeWorkspace()
        if (active.name != name) throw demoFailure("workspace_name_mismatch", 409)
        workspaces.removeAll { it.id == active.id }
        activeWorkspaceId = workspaces.firstOrNull()?.id
        workspaceMembers = mutableListOf(WorkspaceMember("demo-user", DEMO_USER_EMAIL, WorkspaceRole.ADMIN, isSelf = true))
        return currentSession()
    }

    override suspend fun invite(role: WorkspaceRole): String = "demo-invitation-${role.name.lowercase()}"

    override suspend fun members(): WorkspaceMembers = workspaceDetails()

    override suspend fun updateMember(id: String, role: WorkspaceRole) {
        if (role == WorkspaceRole.ADMIN) throw demoFailure("workspace_role_not_allowed", 400)
        val index = workspaceMembers.indexOfFirst { it.userId == id }
        if (index < 0) throw demoFailure("workspace_member_not_found", 404)
        workspaceMembers[index] = workspaceMembers[index].copy(role = role)
    }

    override suspend fun removeMember(id: String) {
        if (workspaceMembers.none { it.userId == id && !it.isSelf }) throw demoFailure("workspace_member_not_found", 404)
        workspaceMembers.removeAll { it.userId == id && !it.isSelf }
    }

    private fun activeWorkspace(): Workspace = workspaces.firstOrNull { it.id == activeWorkspaceId }
        ?: throw demoFailure("workspace_not_found", 404)
}

/** All records are owned by this instance and disappear when the app process creates a new graph. */
class DemoFinanceRepository : FinanceRepository {
    private val cashAccount = Account(1, "日常账户", "cash", currencies = listOf("CNY")) // i18n-allow: demo-fixture
    private val cardAccount = Account(2, "银行卡", "cash", currencies = listOf("CNY")) // i18n-allow: demo-fixture
    private val investmentAccount = Account(3, "证券账户", "investment", currencies = listOf("USD")) // i18n-allow: demo-fixture
    private val accounts = mutableListOf(cashAccount, cardAccount, investmentAccount)
    private val categories = mutableListOf(
        CashCategory("food", name = "餐饮", description = "", depth = 1, sortOrder = 0, revision = 1), // i18n-allow: demo-fixture
        CashCategory("transport", name = "交通", description = "", depth = 1, sortOrder = 1, revision = 1), // i18n-allow: demo-fixture
        CashCategory("income", name = "收入", description = "", depth = 1, sortOrder = 2, revision = 1), // i18n-allow: demo-fixture
    )
    private val records = mutableListOf(
        demoRecord("demo-record-3", "2026-10-10T09:00:00Z", "12000.00", "示例公司", "月度收入", "income", "income"), // i18n-allow: demo-fixture
        demoRecord("demo-record-2", "2026-10-09T12:30:00Z", "-28.00", "山海咖啡", "午后咖啡", "consumption", "food"), // i18n-allow: demo-fixture
        demoRecord("demo-record-1", "2026-10-08T18:15:00Z", "-16.00", "城市交通", "地铁出行", "consumption", "transport"), // i18n-allow: demo-fixture
    )
    private val relations = mutableListOf<CashRelation>()
    private val committedImportTokens = mutableSetOf<String>()
    private val commonFields = mutableMapOf<String, Map<String, StructuredValue>>()
    internal fun currentCommonFields(id: String): Map<String, StructuredValue> = commonFields[id].orEmpty()
    internal fun saveCommonFields(id: String, patch: Map<String, StructuredValue>) {
        findRecord(id)
        require(patch.keys.all { it in setOf("note", "counterparty", "counterparty_account", "counterparty_account_attrs", "category_id") })
        commonFields[id] = commonFields[id].orEmpty() + patch
        if (patch.isNotEmpty()) projectionVersion++
    }
    private fun effectiveRecord(row: CashRecord): CashRecord {
        val fields = currentCommonFields(row.id)
        fun text(key: String, fallback: String) = (fields[key] as? StructuredText)?.value ?: fallback
        return row.copy(note = text("note", row.note), counterparty = text("counterparty", row.counterparty),
            counterpartyAccount = text("counterparty_account", row.counterpartyAccount),
            categoryId = if ("category_id" in fields) (fields["category_id"] as? StructuredText)?.value else row.categoryId)
    }
    private var categoryRevision = 1L
    private var projectionVersion = 1L
    private var nextRecordNumber = 4
    private var nextCategoryNumber = 1
    private var nextRelationNumber = 1
    private var importCommittedRows = 0

    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?): CashPage {
        if (listOfNotNull(filters.amountMin, filters.amountMax).any { !isExactDecimalString(it) }) {
            throw demoFailure("invalid_filter", 400)
        }
        val filtered = records.filter { recordMatches(effectiveRecord(it), filters) }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = filtered.drop(offset).take(DEMO_PAGE_SIZE)
        val next = (offset + page.size).takeIf { it < filtered.size }?.toString()
        return CashPage(
            projectionVersion = projectionVersion,
            items = page.map(::projection),
            nextCursor = next,
            pageSize = page.size,
            filters = mapOf("currency" to filters.currency, "category_id" to filters.categoryId),
            filterOptions = CashFilterOptions(
                categories = categories.toList(),
                currencies = listOf("CNY", "USD"),
                economicTypes = listOf(CashEconomicTypeFilterOption("income"), CashEconomicTypeFilterOption("expense")),
            ),
            monthlySummaries = listOf(CashMonthlySummary("2026-10", listOf(CashMonthlyCurrencySummary("CNY", "12000.00", "44.00")))),
        )
    }

    override suspend fun fetchCashAccounts(): List<Account> = accounts.filter { it.type == "cash" }

    override suspend fun fetchEvidence(id: String): Evidence {
        val record = findRecord(id.removePrefix("projection-"))
        val item = projection(record)
        val evidence = evidenceRecord(record)
        return Evidence(
            projectionVersion = projectionVersion,
            projection = item,
            rootRecord = evidence,
            members = listOf(evidenceMember(record)),
            acceptedRelations = emptyList(),
            inactiveRelationHints = emptyList(),
        )
    }

    override suspend fun fetchLedgerOptions(): LedgerOptions = demoLedgerOptions()

    override suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult {
        if (categoryId != null && categories.none { it.id == categoryId }) throw demoFailure("cash_category_not_found", 404)
        val recordIds = ids.map { it.removePrefix("projection-") }.toSet()
        records.indices.forEach { index ->
            if (records[index].id in recordIds) records[index] = records[index].copy(categoryId = categoryId, category = categoryById(categoryId))
        }
        this.projectionVersion++
        return CashClassificationResult(this.projectionVersion, ids.size, recordIds.intersect(records.map { it.id }.toSet()).size, categoryId)
    }

    override suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact {
        val selected = ids.map { it.removePrefix("projection-") }.toSet()
        return CashProjectionDeleteImpact(
            projectionCount = selected.count { id -> records.any { it.id == id } },
            transactionCount = selected.count { id -> records.any { it.id == id } },
            relationGroupCount = relations.count { it.primaryRecord?.id in selected || it.secondaryRecord?.id in selected },
        )
    }

    override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult {
        val selected = ids.map { it.removePrefix("projection-") }.toSet()
        val removed = records.filter { it.id in selected }
        records.removeAll { it.id in selected }
        relations.removeAll { it.primaryRecord?.id in selected || it.secondaryRecord?.id in selected }
        this.projectionVersion++
        return CashProjectionDeleteResult(removed.size, removed.size, 0, removed.isNotEmpty(), this.projectionVersion)
    }

    override suspend fun fetchCashRecord(id: String): CashRecordDetail = recordDetail(findRecord(id))

    override suspend fun fetchCashRecords(
        query: String?,
        excludeId: String?,
        dateFrom: String?,
        dateTo: String?,
        cursor: String?,
        limit: Int,
    ): CashRecordPage {
        val filtered = records.filter { record ->
            record.id != excludeId &&
                (query.isNullOrBlank() || listOf(record.counterparty, record.counterpartyAccount, record.note, record.accountName).any { it.contains(query, true) }) &&
                (dateFrom == null || record.occurredAt >= dateFrom) && (dateTo == null || record.occurredAt <= dateTo)
        }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = filtered.drop(offset).take(limit.coerceAtLeast(1))
        return CashRecordPage(page, (offset + page.size).takeIf { it < filtered.size }?.toString())
    }

    override suspend fun createCashRecord(write: CashRecordWrite): CashRecordDetail {
        val record = recordFromWrite("demo-record-${nextRecordNumber++}", write)
        records.add(0, record)
        projectionVersion++
        return recordDetail(record)
    }

    override suspend fun updateCashRecord(id: String, write: CashRecordWrite): CashRecordDetail {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_record_not_found", 404)
        records[index] = recordFromWrite(id, write, sourceType = records[index].sourceType)
        projectionVersion++
        return recordDetail(records[index])
    }

    override suspend fun deleteCashRecord(id: String, mode: String): CashRecordDeleteResult {
        val affected = relations.count { it.primaryRecord?.id == id || it.secondaryRecord?.id == id }
        val removed = records.removeAll { it.id == id }
        relations.removeAll { it.primaryRecord?.id == id || it.secondaryRecord?.id == id }
        if (removed) projectionVersion++
        return CashRecordDeleteResult(removed, affected, if (removed) listOf(id) else emptyList())
    }

    override suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetail {
        val primary = findRecord(primaryFactId.removePrefix("fact-").removePrefix("record-"))
        val secondary = findRecord(secondaryFactId.removePrefix("fact-").removePrefix("record-"))
        val relation = CashRelation(
            id = "demo-relation-${nextRelationNumber++}",
            kind = kind,
            label = demoRelationLabel(kind),
            status = "accepted",
            primaryRecord = primary,
            secondaryRecord = secondary,
        )
        relations += relation
        return recordDetail(primary)
    }

    override suspend fun updateCashRelation(id: String, kind: String): CashRecordDetail {
        val index = relations.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_relation_not_found", 404)
        relations[index] = relations[index].copy(kind = kind, label = demoRelationLabel(kind))
        return recordDetail(relations[index].primaryRecord ?: throw demoFailure("cash_record_not_found", 404))
    }

    override suspend fun cancelCashRelation(id: String) {
        relations.removeAll { it.id == id }
    }

    override suspend fun dissolveCashRelations(factId: String): CashRecordDetail {
        val record = findRecord(factId.removePrefix("fact-").removePrefix("record-"))
        relations.removeAll { it.primaryRecord?.id == record.id || it.secondaryRecord?.id == record.id }
        return recordDetail(record)
    }

    override suspend fun fetchCashCategories(): CashCategoryDirectory = CashCategoryDirectory(categoryRevision.toInt(), categories.sortedBy(CashCategory::sortOrder))

    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory {
        if (categories.any { it.parentId == parentId && it.name.equals(name, true) }) throw demoFailure("cash_category_duplicate", 409)
        val parent = categoryById(parentId)
        val category = CashCategory(
            id = "demo-category-${nextCategoryNumber++}",
            parentId = parentId,
            name = name,
            description = description,
            path = parent?.path.orEmpty() + listOfNotNull(parent?.let { CashCategoryPathItem(it.id, it.name) }),
            depth = (parent?.depth ?: 0) + 1,
            sortOrder = categories.size,
            revision = 1,
        )
        categories += category
        categoryRevision++
        return category
    }

    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory {
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_category_not_found", 404)
        val old = categories[index]
        val updated = old.copy(name = name, parentId = parentId, description = description, revision = old.revision + 1)
        categories[index] = updated
        records.indices.forEach { recordIndex ->
            if (records[recordIndex].categoryId == id) records[recordIndex] = records[recordIndex].copy(category = updated)
        }
        categoryRevision++
        return updated
    }

    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategory {
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_category_not_found", 404)
        val targetIndex = when (direction.lowercase()) { "up", "before", "previous" -> index - 1; else -> index + 1 }
        if (targetIndex in categories.indices) {
            val current = categories[index]
            val target = categories[targetIndex]
            categories[index] = target.copy(sortOrder = current.sortOrder)
            categories[targetIndex] = current.copy(sortOrder = target.sortOrder)
        }
        categoryRevision++
        return categories.first { it.id == id }
    }

    override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact {
        if (categories.none { it.id == id }) throw demoFailure("cash_category_not_found", 404)
        return CashCategoryDeleteImpact(
            categoryId = id,
            revision = categoryRevision,
            categoryRevision = categories.first { it.id == id }.revision.toLong(),
            childCount = categories.count { it.parentId == id },
            directUsageCount = records.count { it.categoryId == id },
        )
    }

    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult {
        val children = categories.filter { it.parentId == id }.map(CashCategory::id).toSet()
        val removedIds = children + id
        val clearedCount = records.count { it.categoryId in removedIds }
        records.indices.forEach { index -> if (records[index].categoryId in removedIds) records[index] = records[index].copy(category = null, categoryId = null) }
        categories.removeAll { it.id in removedIds }
        categoryRevision++
        projectionVersion++
        return CashCategoryDeleteResult(id, clearedCount, categoryRevision)
    }

    override suspend fun detectCashImport(file: FileSource, currency: String?, password: String?): ImportDetection {
        ensureDemoStatement(file)
        file.read()
        return ImportDetection("bank", "bank", ImportFileDescriptor(file.name, DEMO_STATEMENT_DIGEST), DEMO_STATEMENT_DIGEST, DEMO_IMPORT_ROWS.size)
    }

    override suspend fun scanCashImport(files: List<FileSource>, currency: String?, passwords: Map<String, String>, importToken: String?): ImportScan {
        if (files.isEmpty()) throw demoFailure("import_file_required", 400)
        files.forEach { ensureDemoStatement(it); it.read() }
        val token = importToken ?: "demo-import-$DEMO_STATEMENT_DIGEST"
        val fileScans = files.mapIndexed { index, file ->
            ImportFileScan(index, file.name, file.name, DEMO_STATEMENT_DIGEST, file.size ?: 0L, "bank", "bank", DEMO_IMPORT_ROWS.size, "ready")
        }
        return ImportScan(
            importToken = token,
            contract = "cash-import-v1",
            channel = "bank",
            channelLabel = "bank",
            ready = true,
            batchDigest = DEMO_STATEMENT_DIGEST,
            channels = listOf("bank"),
            files = fileScans,
            file = ImportFileDescriptor(files.first().name, DEMO_STATEMENT_DIGEST),
            digest = DEMO_STATEMENT_DIGEST,
            accounts = accounts.filter { it.type == "cash" },
            groups = listOf(
                ImportSourceGroup(
                    groupId = "demo-bank-account",
                    sourceType = "bank_statement",
                    channel = "bank",
                    channelLabel = "bank",
                    displayName = cashAccount.name,
                    maskedEvidence = "尾号 1234", // i18n-allow: demo-fixture
                    currencies = listOf("CNY"),
                    rowCount = DEMO_IMPORT_ROWS.size,
                    suggestion = ImportMappingSuggestion(accountId = cashAccount.id, account = cashAccount, mappingRevision = 1),
                ),
            ),
        )
    }

    override suspend fun previewCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        mapping: List<ImportMappingDecision>?,
        batch: Boolean,
    ): ImportPreview {
        val token = requireDemoImportToken(importToken)
        val choices = mapping.orEmpty().map { decision ->
            ImportMappingResult(
                groupId = decision.groupId,
                sourceType = "bank_statement",
                channel = "bank",
                channelLabel = "bank",
                accountId = decision.accountId,
                newAccount = decision.newAccount,
            )
        }.ifEmpty {
            listOf(ImportMappingResult("demo-bank-account", "bank_statement", "bank", "bank", cashAccount.id))
        }
        val file = ImportFileDescriptor(DEMO_STATEMENT_NAME, DEMO_STATEMENT_DIGEST)
        return ImportPreview(
            importToken = token,
            channel = "bank",
            channelLabel = "bank",
            file = file,
            batchDigest = DEMO_STATEMENT_DIGEST,
            channels = listOf("bank"),
            relationDigest = "demo-relations-$DEMO_STATEMENT_DIGEST",
            columns = listOf("日期", "账户", "对方", "金额", "币种", "备注"), // i18n-allow: demo-fixture
            items = DEMO_IMPORT_ROWS.map { row ->
                ImportPreviewItem(
                    recordId = row.recordId,
                    relationRef = row.relationRef,
                    occurredAt = row.occurredAt,
                    amount = row.amount,
                    currency = "CNY",
                    accountName = row.accountName,
                    counterparty = row.counterparty,
                    recordType = row.recordType,
                    recordSubtype = "not_applicable",
                    category = categoryById(row.categoryId)?.name.orEmpty(),
                    note = row.note,
                    channel = "bank",
                    status = "new",
                )
            },
            summary = ImportPreviewSummary(DEMO_IMPORT_ROWS.size, DEMO_IMPORT_ROWS.size - importCommittedRows, importCommittedRows, 0),
            mapping = choices,
            relations = listOf(demoImportRelation()),
        )
    }

    override suspend fun commitCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        previewDigest: String?,
        previewRelationDigest: String?,
        previewChannel: String?,
        relations: List<StructuredObject>?,
        mapping: List<ImportMappingDecision>?,
        idempotencyKey: String?,
        batch: Boolean,
    ): ImportCommitResult {
        val token = requireDemoImportToken(importToken)
        if (previewDigest != null && previewDigest != DEMO_STATEMENT_DIGEST) throw demoFailure("import_preview_changed", 409)
        if (!committedImportTokens.add(idempotencyKey ?: token)) {
            return ImportCommitResult("", 0, 0, skippedRows = DEMO_IMPORT_ROWS.size, channel = "bank", digest = DEMO_STATEMENT_DIGEST)
        }
        val decision = mapping.orEmpty().firstOrNull { it.groupId == "demo-bank-account" }
        decision?.newAccount?.let { draft ->
            val newAccount = Account(accounts.maxOfOrNull(Account::id)?.plus(1) ?: 4, draft.name, draft.type, currencies = draft.currencies)
            accounts += newAccount
        }
        val selectedAccount = accounts.firstOrNull { it.id == decision?.accountId } ?: cashAccount
        DEMO_IMPORT_ROWS.forEach { row ->
            val id = "demo-imported-${nextRecordNumber++}"
            records.add(0, CashRecord(
                id = id,
                occurredAt = row.occurredAt,
                amount = row.amount,
                currency = "CNY",
                counterparty = row.counterparty,
                note = row.note,
                category = categoryById(row.categoryId),
                categoryId = row.categoryId,
                recordType = row.recordType,
                accountName = selectedAccount.name,
                accountId = selectedAccount.id,
                accountType = selectedAccount.type,
                sourceType = "statement_import",
            ))
        }
        importCommittedRows += DEMO_IMPORT_ROWS.size
        projectionVersion++
        return ImportCommitResult(
            message = "",
            newRows = DEMO_IMPORT_ROWS.size,
            updatedRows = 0,
            channel = "bank",
            digest = DEMO_STATEMENT_DIGEST,
            batchDigest = DEMO_STATEMENT_DIGEST,
            channels = listOf("bank"),
            pendingRelations = 1,
        )
    }

    override suspend fun fetchInvestmentAccounts(): List<Account> = listOf(investmentAccount)

    override suspend fun fetchInvestmentPage(filters: InvestmentFilters, cursor: String?): InvestmentPage {
        val events = demoInvestmentEvents.filter { event ->
            (filters.accountId == null || event.account.id.toString() == filters.accountId) &&
                (filters.recordType == null || event.recordType == filters.recordType) &&
                (filters.ticker == null || event.fromAsset.ticker == filters.ticker || event.toAsset.ticker == filters.ticker)
        }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = events.drop(offset).take(DEMO_PAGE_SIZE)
        return InvestmentPage(1, page, (offset + page.size).takeIf { it < events.size }?.toString(), page.size)
    }

    override suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidence {
        val event = demoInvestmentEvents.firstOrNull { it.eventId == eventId } ?: throw demoFailure("investment_event_not_found", 404)
        return InvestmentEvidence(1, event, relations = event.relations)
    }

    override suspend fun fetchInvestmentPortfolio(displayCurrency: String?, period: String, phase: String): Portfolio = demoPortfolio(displayCurrency ?: "USD")

    override suspend fun refreshInvestmentPortfolio(displayCurrency: String?, period: String) = Unit

    override suspend fun streamInvestmentPortfolio(
        displayCurrency: String?,
        period: String,
        onPortfolio: (Portfolio) -> Unit,
        onRefreshError: () -> Unit,
    ) {
        onPortfolio(demoPortfolio(displayCurrency ?: "USD"))
    }

    private fun projection(source: CashRecord): CashProjection {
        val record = effectiveRecord(source)
        return CashProjection(
        projectionId = "projection-${record.id}",
        occurredAt = record.occurredAt,
        account = accountById(record.accountId),
        counterparty = record.counterparty,
        category = categoryById(record.categoryId),
        note = record.note,
        amount = record.amount,
        currency = record.currency,
        economicType = if (record.recordType == "income") "income" else if (record.recordType == "transfer") "transfer" else "expense",
        transferSubtype = record.recordSubtype.takeIf { record.recordType == "transfer" },
        composition = if (record.components.isNullOrEmpty()) listOf("single") else listOf("combined"),
        recordId = record.id,
        transactionId = record.id,
        sourceType = record.sourceType,
        sourceTypes = listOfNotNull(record.sourceType),
    )

    }

    private fun recordDetail(record: CashRecord) = CashRecordDetail(
        record = record.copy(category = categoryById(record.categoryId)),
        relations = relations.filter { it.primaryRecord?.id == record.id || it.secondaryRecord?.id == record.id },
        options = demoLedgerOptions(),
    )

    private fun evidenceRecord(record: CashRecord) = EvidenceRecord(
        id = "fact-${record.id}",
        occurredAt = record.occurredAt,
        account = accountById(record.accountId),
        accountName = record.accountName,
        accountId = record.accountId,
        accountType = record.accountType,
        counterparty = record.counterparty,
        counterpartyAccount = record.counterpartyAccount,
        category = categoryById(record.categoryId),
        categoryId = record.categoryId,
        note = record.note,
        amount = record.amount,
        currency = record.currency,
        sourceType = record.sourceType,
        recordId = record.id,
        recordType = record.recordType,
        recordSubtype = record.recordSubtype,
        cashGranularity = record.cashGranularity,
        components = record.components,
    )

    private fun evidenceMember(record: CashRecord) = EvidenceMember(
        id = "fact-${record.id}",
        occurredAt = record.occurredAt,
        account = accountById(record.accountId),
        accountName = record.accountName,
        accountId = record.accountId,
        accountType = record.accountType,
        counterparty = record.counterparty,
        counterpartyAccount = record.counterpartyAccount,
        category = categoryById(record.categoryId),
        categoryId = record.categoryId,
        note = record.note,
        amount = record.amount,
        currency = record.currency,
        sourceType = record.sourceType,
        recordId = record.id,
        recordType = record.recordType,
        recordSubtype = record.recordSubtype,
        cashGranularity = record.cashGranularity,
        components = record.components,
    )

    private fun recordFromWrite(id: String, write: CashRecordWrite, sourceType: String? = null) = CashRecord(
        id = id,
        occurredAt = write.occurredAt,
        amount = write.amount,
        currency = write.currency,
        counterparty = write.counterparty,
        counterpartyAccount = write.counterpartyAccount,
        note = write.note,
        category = categoryById(write.categoryId),
        categoryId = write.categoryId,
        recordType = write.recordType,
        recordSubtype = write.recordSubtype,
        accountName = write.accountName,
        accountId = accounts.firstOrNull { it.name == write.accountName }?.id,
        accountType = accounts.firstOrNull { it.name == write.accountName }?.type ?: "cash",
        sourceType = sourceType,
    )

    private fun recordMatches(record: CashRecord, filters: CashFilters): Boolean {
        val projected = projection(record)
        val minimum = filters.amountMin?.let { compareDemoDecimals(record.amount, it) >= 0 } ?: true
        val maximum = filters.amountMax?.let { compareDemoDecimals(record.amount, it) <= 0 } ?: true
        return minimum && maximum &&
            (filters.accountId == null || record.accountId?.toString() == filters.accountId) &&
            (filters.counterparty.isNullOrBlank() || record.counterparty.contains(filters.counterparty, true)) &&
            (filters.categoryId == null || record.categoryId == filters.categoryId) &&
            (filters.uncategorized != "true" || record.categoryId == null) &&
            (filters.currency == null || record.currency == filters.currency) &&
            (filters.dateFrom == null || record.occurredAt >= filters.dateFrom) &&
            (filters.dateTo == null || record.occurredAt <= filters.dateTo) &&
            (filters.economicType == null || projected.economicType == filters.economicType) &&
            (filters.transferSubtype == null || projected.transferSubtype == filters.transferSubtype) &&
            (filters.composition == null || filters.composition in projected.composition)
    }

    private fun findRecord(id: String): CashRecord = records.firstOrNull { it.id == id || "fact-${it.id}" == id }
        ?: throw demoFailure("cash_record_not_found", 404)

    private fun categoryById(id: String?): CashCategory? = categories.firstOrNull { it.id == id }
    private fun accountById(id: Long?): Account? = accounts.firstOrNull { it.id == id }

    private fun ensureDemoStatement(file: FileSource) {
        if (file.name != DEMO_STATEMENT_NAME) throw demoFailure("demo_statement_only", 400)
    }

    private fun requireDemoImportToken(token: String): String {
        if (!token.startsWith("demo-import-")) throw demoFailure("import_token_invalid", 404)
        return token
    }

    private fun demoImportRelation(): ImportRelation {
        fun relationRecord(row: DemoImportRow, preview: Boolean) = ImportRelationRecord(
            recordId = row.recordId,
            relationRef = row.relationRef,
            occurredAt = row.occurredAt,
            amount = row.amount,
            currency = "CNY",
            accountName = row.accountName,
            counterparty = row.counterparty,
            recordType = row.recordType,
            recordSubtype = "not_applicable",
            category = categoryById(row.categoryId)?.name.orEmpty(),
            note = row.note,
            channel = "bank",
            status = "new",
            preview = preview,
        )
        val first = DEMO_IMPORT_ROWS.first()
        val second = DEMO_IMPORT_ROWS.last()
        return ImportRelation(
            id = "demo-payment-review",
            kind = "payment_mirror",
            label = "",
            subtype = "",
            status = "pending",
            automatic = false,
            ruleId = "demo-rule",
            reason = "",
            primary = relationRecord(first, true),
            secondary = relationRecord(second, true),
            candidates = listOf(relationRecord(second, true)),
        )
    }

    private fun demoPortfolio(displayCurrency: String) = Portfolio(
        accounts = listOf(
            PortfolioAccount(
                investmentAccount.name,
                "USD",
                listOf(
                    PortfolioPosition(
                        ticker = "AAPL",
                        displayName = "Apple",
                        shares = "2",
                        totalCost = "360.00",
                        costCurrency = "USD",
                        currentPrice = "221.00",
                        marketValue = "442.00",
                        profit = "82.00",
                        quoteStatus = "available",
                        quoteCurrency = "USD",
                        displayCurrency = displayCurrency,
                        displayMarketValue = "442.00",
                        usdMarketValue = "442.00",
                    ),
                ),
            ),
        ),
        totalMarketValue = "442.00",
        totalProfit = "82.00",
        totalProfitRate = "22.78",
        periodProfit = "4.20",
        periodProfitRate = "0.96",
    )

    private val demoInvestmentEvents = listOf(
        InvestmentEvent(
            eventId = "demo-investment-event-2",
            occurredAt = "2026-10-10T14:00:00Z",
            account = investmentAccount,
            recordType = "buy",
            currency = "USD",
                note = "定期买入", // i18n-allow: demo-fixture
            toAsset = InvestmentAsset("AAPL", "2"),
            commission = InvestmentCommission("1.00", "USD"),
            recordId = "demo-investment-record-2",
        ),
        InvestmentEvent(
            eventId = "demo-investment-event-1",
            occurredAt = "2026-10-05T14:00:00Z",
            account = investmentAccount,
            recordType = "buy",
            currency = "USD",
                note = "首次买入", // i18n-allow: demo-fixture
            toAsset = InvestmentAsset("AAPL", "1"),
            commission = InvestmentCommission("1.00", "USD"),
            recordId = "demo-investment-record-1",
        ),
    )
}

private data class DemoImportRow(
    val recordId: String,
    val relationRef: String,
    val occurredAt: String,
    val amount: String,
    val accountName: String,
    val counterparty: String,
    val recordType: String,
    val categoryId: String,
    val note: String,
)

private val DEMO_IMPORT_ROWS = listOf(
    DemoImportRow("demo-import-row-1", "payment-1", "2026-10-09T04:30:00Z", "-28.00", "日常账户", "山海咖啡", "consumption", "food", "午后咖啡"), // i18n-allow: demo-fixture
    DemoImportRow("demo-import-row-2", "payment-1", "2026-10-10T01:00:00Z", "12000.00", "日常账户", "示例公司", "income", "income", "月度收入"), // i18n-allow: demo-fixture
)

private fun demoRecord(id: String, occurredAt: String, amount: String, counterparty: String, note: String, recordType: String, categoryId: String) = CashRecord(
    id = id,
    occurredAt = occurredAt,
    amount = amount,
    currency = "CNY",
    counterparty = counterparty,
    note = note,
    categoryId = categoryId,
    recordType = recordType,
    accountName = "日常账户", // i18n-allow: demo-fixture
    accountId = 1,
    accountType = "cash",
    sourceType = "manual",
)

private fun demoLedgerOptions() = LedgerOptions(
    recordTypes = listOf(
        RecordTypeOption("consumption", "消费", listOf(SimpleOption("not_applicable", "普通消费"))), // i18n-allow: demo-fixture
        RecordTypeOption("income", "收入", listOf(SimpleOption("not_applicable", "普通收入"))), // i18n-allow: demo-fixture
        RecordTypeOption("transfer", "转账", listOf(SimpleOption("internal", "内部转账"))), // i18n-allow: demo-fixture
    ),
    relationTypes = listOf(SimpleOption("payment_mirror", "同笔支付"), SimpleOption("refund", "退款")), // i18n-allow: demo-fixture
)

private fun demoRelationLabel(kind: String): String =
    demoLedgerOptions().relationTypes.firstOrNull { it.value == kind }?.label.orEmpty()

private const val DEMO_PAGE_SIZE = 20

private fun demoFailure(code: String, status: Int) = DomainFailure(code, status, FailureCategory.RECOVERABLE)

private data class DemoDecimalParts(val sign: Int, val whole: String, val fraction: String)

private fun compareDemoDecimals(first: String, second: String): Int {
    val a = parseDemoDecimal(first)
    val b = parseDemoDecimal(second)
    if (a.sign != b.sign) return a.sign.compareTo(b.sign)
    val wholeComparison = when {
        a.whole.length != b.whole.length -> a.whole.length.compareTo(b.whole.length)
        else -> a.whole.compareTo(b.whole)
    }
    val fractionWidth = maxOf(a.fraction.length, b.fraction.length)
    val magnitudeComparison = if (wholeComparison != 0) wholeComparison else {
        a.fraction.padEnd(fractionWidth, '0').compareTo(b.fraction.padEnd(fractionWidth, '0'))
    }
    return if (a.sign < 0) -magnitudeComparison else magnitudeComparison
}

private fun parseDemoDecimal(value: String): DemoDecimalParts {
    val sign = if (value.startsWith('-')) -1 else 1
    val unsigned = value.removePrefix("-").removePrefix("+")
    val separator = unsigned.indexOf('.')
    val whole = (if (separator < 0) unsigned else unsigned.substring(0, separator))
        .trimStart('0')
        .ifEmpty { "0" }
    val fraction = if (separator < 0) "" else unsigned.substring(separator + 1).trimEnd('0')
    return DemoDecimalParts(if (whole == "0" && fraction.isEmpty()) 1 else sign, whole, fraction)
}
