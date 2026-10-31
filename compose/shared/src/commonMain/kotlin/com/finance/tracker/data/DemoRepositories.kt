package com.finance.tracker.data

import com.finance.tracker.core.getLocalDateForInstant
import com.finance.tracker.domain.*

private const val DEMO_WORKSPACE_ID = "demo-workspace"
private const val DEMO_USER_EMAIL = "demo@example.test"
private const val DEMO_STATEMENT_NAME = "演示银行账单.csv" // i18n-allow: demo-fixture

/** An in-memory statement fixture. Its bytes are synthetic and never come from the platform file system. */
class DemoStatementFileSource(internal val auditScenarios: Boolean = false) : BoundedFileSource {
    override val name: String = DEMO_STATEMENT_NAME
    override val mediaType: String = "text/csv"
    internal val rows = if (auditScenarios) demoAuditImportRows() else demoBasicImportRows()
    private val bytes = ("日期,账户,对方,金额,币种,备注\n" + rows.joinToString("\n") { row -> // i18n-allow: demo-fixture
        listOf(row.values["occurred_at"].orEmpty(), row.accountName, row.values["counterparty"].orEmpty(),
            row.values["amount"].orEmpty(), row.currency.orEmpty(), row.values["note"].orEmpty()).joinToString(",")
    }).encodeToByteArray()
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

internal fun demoCashPostingComplete(fields: StructuredObject, accounts: List<Account>): Boolean {
    fun text(key: String) = (fields.fields[key] as? StructuredText)?.value.orEmpty()
    val account = accounts.firstOrNull { it.id.toString() == text("account_id") && it.active && it.type in setOf("cash", "loan", "lend") } ?: return false
    return text("currency") in account.currencies.orEmpty() && isExactDecimalString(text("amount")) &&
        text("record_type").isNotBlank() && text("record_subtype").isNotBlank() &&
        runCatching { kotlin.time.Instant.parse(text("occurred_at")) }.isSuccess
}

/** All records are owned by this instance and disappear when the app process creates a new graph. */
class DemoFinanceRepository : FinanceRepository {
    private val cashAccount = Account(1, "日常账户", "cash", currencies = listOf("CNY")) // i18n-allow: demo-fixture
    private val cardAccount = Account(2, "银行卡", "cash", currencies = listOf("CNY", "USD")) // i18n-allow: demo-fixture
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
    private val manualTransfers = mutableMapOf<String, List<String>>()
    internal fun transferMembers(id: String): List<String> = manualTransfers.values.firstOrNull { id in it }.orEmpty()
    internal val auditRelations = mutableListOf<DemoAuditRelation>()
    internal val auditSuggestions = mutableMapOf<String, ImportPairOption>()
    internal val auditLabels = mutableMapOf<String, String>()
    internal fun auditMembers(id: String): List<String> {
        val connected = mutableSetOf(id)
        do {
            val oldSize = connected.size
            auditRelations.filter { it.active && it.state == "confirmed" && it.members.any(connected::contains) }
                .forEach { connected.addAll(it.members) }
        } while (connected.size != oldSize)
        return records.filter { it.id in connected }.map { it.id }
    }
    internal fun transferRoot(id: String): String = transferMembers(id).firstOrNull()
        ?: auditMembers(id).firstOrNull() ?: id
    internal fun projectionMembers(id: String): List<String> = transferMembers(id).ifEmpty { auditMembers(id) }
    internal fun pendingRelations(id: String) = auditRelations.any { it.active && it.state == "draft" && id in it.members }
    internal fun savedRelations(id: String) = auditRelations.filter { id in it.members }.map {
        CashSavedRelation(it.id, it.kind, it.state, it.active, it.revision, it.suggestionId)
    }
    internal fun auditRecords() = records.toList()
    internal fun seedAuditRecord(id: String, fields: StructuredObject, state: String, label: String? = null) {
        writeImported(id, fields, state, create = true)
        val row = records.removeAt(0); records.add(row)
        label?.let { auditLabels[id] = it }
    }
    internal fun auditRelationsChanged() { projectionVersion++ }

    internal val importedFields = mutableMapOf<String, StructuredObject>()
    internal val importedStates = mutableMapOf<String, String>()
    internal fun demoAccounts() = accounts.toList()
    internal fun demoAccountInUse(id: String) = records.any { it.accountId?.toString() == id }
    internal fun saveDemoAccount(id: String?, name: String, type: String, active: Boolean, currencies: List<String>): Account {
        val next = Account(id?.toLong() ?: ((accounts.maxOfOrNull { it.id } ?: 0) + 1), name, type, active, currencies)
        accounts.removeAll { it.id == next.id }; accounts.add(next)
        return next
    }
    internal fun deleteDemoAccount(id: String) { accounts.removeAll { it.id.toString() == id } }
    internal fun insertImported(fields: StructuredObject, state: String): String {
        val id = "demo-imported-${nextRecordNumber++}"
        writeImported(id, fields, state, create = true)
        return id
    }
    internal fun updateImported(id: String, fields: StructuredObject, state: String) {
        require(id in importedFields)
        writeImported(id, fields, state, create = false)
    }
    private fun writeImported(id: String, fields: StructuredObject, state: String, create: Boolean) {
        fun text(key: String) = (fields.fields[key] as? StructuredText)?.value.orEmpty()
        val accountName = accounts.firstOrNull { it.id.toString() == text("account_id") }?.name.orEmpty()
        val row = recordFromWrite(id, CashRecordWrite(text("occurred_at"), text("amount"), text("currency"), text("counterparty"),
            text("counterparty_account"), text("note"), accountName, text("record_type"), text("record_subtype"), text("category_id").ifEmpty { null }), text("source_type").ifEmpty { "statement_import" })
        if (create) records.add(0, row) else records[records.indexOfFirst { it.id == id }] = row
        importedFields[id] = fields; importedStates[id] = state
        projectionVersion++
    }
    private val commonFields = mutableMapOf<String, Map<String, StructuredValue>>()
    internal fun currentCommonFields(id: String): Map<String, StructuredValue> = commonFields[transferRoot(id)].orEmpty()
    internal fun saveCommonFields(id: String, patch: Map<String, StructuredValue>) {
        findRecord(id)
        require(patch.keys.all { it in setOf("note", "counterparty", "counterparty_account", "counterparty_account_attrs", "category_id") })
        val root = transferRoot(id)
        commonFields[root] = commonFields[root].orEmpty() + patch
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

    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?, projectionVersion: Long?, limit: Int): CashPage {
        if (listOfNotNull(filters.amountMin, filters.amountMax).any { !isExactDecimalString(it) }) {
            throw demoFailure("invalid_filter", 400)
        }
        val filtered = records.filter { transferRoot(it.id) == it.id }.filter { row ->
            val displayedAmount = projection(row).amount
            val amountVisible = !filters.hideZeroAmount || displayedAmount == null || compareDemoDecimals(displayedAmount, "0") != 0
            amountVisible && projectionMembers(row.id).any { recordMatches(effectiveRecord(findRecord(it)), filters) }
        }.sortedWith(compareByDescending<CashRecord> { it.occurredAt }.thenByDescending { it.id })
        if (projectionVersion != null && projectionVersion != this.projectionVersion) {
            throw demoFailure("read_snapshot_changed", 409)
        }
        val offset = (startIndex ?: cursor?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val page = filtered.drop(offset).take(limit.coerceIn(1, DEMO_PAGE_SIZE))
        val next = (offset + page.size).takeIf { startIndex == null && it < filtered.size }?.toString()
        val monthPositions = if (startIndex != null && projectionVersion == null) {
            val positions = mutableListOf<CashMonthPosition>()
            filtered.forEachIndexed { index, record ->
                val month = projection(record).occurredAt?.let { getLocalDateForInstant(it).take(7) } ?: "unknown"
                if (positions.lastOrNull()?.month == month) {
                    val previous = positions.removeAt(positions.lastIndex)
                    positions += previous.copy(endIndex = index + 1)
                } else positions += CashMonthPosition(month, index, index + 1)
            }
            positions
        } else emptyList()
        return CashPage(
            projectionVersion = this.projectionVersion,
            items = page.map(::projection),
            nextCursor = next,
            pageSize = page.size,
            filters = mapOf("currency" to filters.currency, "category_id" to filters.categoryId, "hide_zero_amount" to filters.hideZeroAmount.toString()),
            filterOptions = CashFilterOptions(
                categories = categories.toList(),
                currencies = records.mapNotNull { projection(it).currency }.distinct().sorted(),
                economicTypes = records.map(::projection).groupBy { it.economicType }.mapNotNull { (type, rows) ->
                    type?.let { CashEconomicTypeFilterOption(it, rows.mapNotNull { it.businessLabel }.distinct()) }
                },
            ),
            monthlySummaries = if (startIndex == null || projectionVersion == null) {
                demoMonthlySummaries(filtered.map(::projection))
            } else {
                emptyList()
            },
            pendingItemCount = filtered.count { projection(it).attentionCount > 0 }.toLong(),
            monthPositions = monthPositions,
            startIndex = startIndex,
            totalCount = filtered.size.takeIf { startIndex != null },
            itemsBefore = startIndex?.coerceAtMost(filtered.size),
            itemsAfter = startIndex?.let { (filtered.size - it - page.size).coerceAtLeast(0) },
        )
    }

    override suspend fun fetchCashAccounts(): List<Account> = accounts.filter { it.type == "cash" }

    override suspend fun fetchEvidence(id: String): Evidence {
        val record = findRecord(transferRoot(id.removePrefix("projection-")))
        val members = projectionMembers(record.id).map(::findRecord)
        val item = projection(record)
        val evidence = evidenceRecord(record)
        return Evidence(
            projectionVersion = projectionVersion,
            projection = item,
            rootRecord = evidence,
            members = members.map(::evidenceMember),
            acceptedRelations = relations.filter { it.kind == "transfer_pair" && it.primaryRecord?.id == record.id }.map {
                AcceptedEvidenceRelation(it.id, it.kind, primaryRecord = it.primaryRecord?.let(::evidenceRecord), secondaryRecord = it.secondaryRecord?.let(::evidenceRecord))
            } + auditRelations.filter { it.active && it.state == "confirmed" && it.members.any { id -> members.any { it.id == id } } }.map {
                AcceptedEvidenceRelation(it.id, it.kind, primaryRecord = evidenceRecord(findRecord(it.members[0])), secondaryRecord = evidenceRecord(findRecord(it.members[1])))
            },
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
        val roots = ids.map { it.removePrefix("projection-") }.toSet()
        val selected = roots.flatMap { transferMembers(it).ifEmpty { listOf(it) } }.toSet()
        return CashProjectionDeleteImpact(
            projectionCount = roots.count { id -> records.any { it.id == id } },
            transactionCount = selected.count { id -> records.any { it.id == id } },
            relationGroupCount = relations.count { it.primaryRecord?.id in selected || it.secondaryRecord?.id in selected },
        )
    }

    override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult {
        val roots = ids.map { it.removePrefix("projection-") }.toSet()
        val selected = roots.flatMap { transferMembers(it).ifEmpty { listOf(it) } }.toSet()
        val groupCount = relations.count { it.primaryRecord?.id in selected || it.secondaryRecord?.id in selected }
        val removed = records.filter { it.id in selected }
        records.removeAll { it.id in selected }
        relations.removeAll { it.primaryRecord?.id in selected || it.secondaryRecord?.id in selected }
        manualTransfers.entries.removeAll { it.key in selected }
        this.projectionVersion++
        return CashProjectionDeleteResult(roots.count { root -> removed.any { it.id == root } }, removed.size, groupCount, removed.isNotEmpty(), this.projectionVersion)
    }

    suspend fun fetchCashRecord(id: String): CashRecordDetail = recordDetail(findRecord(id))

    suspend fun fetchCashRecords(
        query: String? = null,
        excludeId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        cursor: String? = null,
        limit: Int = 20,
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

    suspend fun createCashRecord(write: CashRecordWrite): CashRecordDetail {
        val record = recordFromWrite("demo-record-${nextRecordNumber++}", write)
        records.add(0, record)
        projectionVersion++
        return recordDetail(record)
    }

    internal fun createDemoCashTransfer(input: StructuredObject) {
        fun text(key: String) = (input.fields[key] as? StructuredText)?.value.orEmpty()
        val from = accounts.singleOrNull { it.name == text("from_account") && it.active } ?: throw demoFailure("invalid_account", 422)
        val to = accounts.singleOrNull { it.name == text("to_account") && it.active } ?: throw demoFailure("invalid_account", 422)
        val fromCurrency = text("from_currency"); val toCurrency = text("to_currency")
        val exchange = fromCurrency != toCurrency
        val amount = text("amount"); val toAmount = if (exchange) text("to_amount") else amount
        if (fromCurrency !in from.currencies.orEmpty() || toCurrency !in to.currencies.orEmpty() ||
            !isExactDecimalString(amount) || !isExactDecimalString(toAmount) || amount.startsWith('-') || toAmount.startsWith('-') ||
            amount.none { it in '1'..'9' } || toAmount.none { it in '1'..'9' } || from.id == to.id && !exchange) throw demoFailure("invalid_record", 422)
        val time = text("date") + "T" + text("time") + "Z"
        val subtype = if (exchange) "currency_exchange" else "ordinary_transfer"
        val outgoing = recordFromWrite("demo-record-${nextRecordNumber++}", CashRecordWrite(time, "-" + amount.removePrefix("+"), fromCurrency,
            to.name, note = text("note"), accountName = from.name, recordType = if (exchange) "fx_out" else "transfer_out", recordSubtype = subtype))
        val incoming = recordFromWrite("demo-record-${nextRecordNumber++}", CashRecordWrite(time, toAmount.removePrefix("+"), toCurrency,
            from.name, note = text("note"), accountName = to.name, recordType = if (exchange) "fx_in" else "transfer_in", recordSubtype = subtype))
        records.addAll(0, listOf(outgoing, incoming)); manualTransfers[outgoing.id] = listOf(outgoing.id, incoming.id)
        relations += CashRelation("demo-relation-${nextRelationNumber++}", "transfer_pair", "", status = "accepted", primaryRecord = outgoing, secondaryRecord = incoming)
        projectionVersion++
    }

    internal fun prepareTransferMembers(id: String, patches: List<CashMemberPatch>): List<CashRecord> {
        val ids = transferMembers(id)
        val allowed = setOf("amount", "currency", "account_id", "occurred_at")
        if (ids.size != 2 || patches.size != 2 || patches.map { it.transactionId }.toSet() != ids.toSet()) throw demoFailure("invalid_transfer_members", 422)
        val next = ids.map { memberId ->
            val row = findRecord(memberId); val patch = patches.single { it.transactionId == memberId }.patch.fields
            if (patch.keys.any { it !in allowed } || patch.values.any { it !is StructuredText }) throw demoFailure("field_not_editable", 422)
            fun value(key: String, fallback: String) = (patch[key] as? StructuredText)?.value ?: fallback
            val account = accounts.singleOrNull { it.id.toString() == value("account_id", row.accountId.toString()) && it.active && it.type == "cash" }
                ?: throw demoFailure("invalid_account", 422)
            val currency = value("currency", row.currency); val amount = value("amount", row.amount)
            val time = value("occurred_at", row.occurredAt)
            if (currency !in account.currencies.orEmpty() || !isExactDecimalString(amount) || amount.none { it in '1'..'9' } ||
                !Regex("\\d{4}-\\d{2}-\\d{2}T.*Z").matches(time)) throw demoFailure("invalid_record", 422)
            row.copy(amount = amount, currency = currency, occurredAt = time, accountId = account.id, accountName = account.name, accountType = account.type)
        }
        if (!next[0].amount.startsWith('-') || next[1].amount.startsWith('-')) throw demoFailure("invalid_amount", 422)
        val exchange = next[0].currency != next[1].currency
        if (!exchange && (next[0].accountId == next[1].accountId || compareDemoDecimals(next[0].amount.removePrefix("-"), next[1].amount) != 0))
            throw demoFailure("invalid_transfer_amount", 422)
        return next.mapIndexed { index, row -> row.copy(recordType = if (exchange) { if (index == 0) "fx_out" else "fx_in" }
            else { if (index == 0) "transfer_out" else "transfer_in" }, recordSubtype = if (exchange) "currency_exchange" else "ordinary_transfer") }
    }
    internal fun saveTransferMembers(id: String, patches: List<CashMemberPatch>): List<String> {
        val next = prepareTransferMembers(id, patches)
        val changed = next.filter { it != findRecord(it.id) }.map { it.id }
        next.forEach { row -> records[records.indexOfFirst { it.id == row.id }] = row }
        relations.indices.filter { relations[it].primaryRecord?.id == next[0].id && relations[it].secondaryRecord?.id == next[1].id }.forEach { index ->
            relations[index] = relations[index].copy(primaryRecord = next[0], secondaryRecord = next[1])
        }
        projectionVersion++
        return changed
    }

    suspend fun updateCashRecord(id: String, write: CashRecordWrite): CashRecordDetail {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_record_not_found", 404)
        records[index] = recordFromWrite(id, write, sourceType = records[index].sourceType)
        projectionVersion++
        return recordDetail(records[index])
    }

    suspend fun deleteCashRecord(id: String, mode: String): CashRecordDeleteResult {
        val affected = relations.count { it.primaryRecord?.id == id || it.secondaryRecord?.id == id }
        val removed = records.removeAll { it.id == id }
        relations.removeAll { it.primaryRecord?.id == id || it.secondaryRecord?.id == id }
        if (removed) projectionVersion++
        return CashRecordDeleteResult(removed, affected, if (removed) listOf(id) else emptyList())
    }

    suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetail {
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

    suspend fun updateCashRelation(id: String, kind: String): CashRecordDetail {
        val index = relations.indexOfFirst { it.id == id }
        if (index < 0) throw demoFailure("cash_relation_not_found", 404)
        relations[index] = relations[index].copy(kind = kind, label = demoRelationLabel(kind))
        return recordDetail(relations[index].primaryRecord ?: throw demoFailure("cash_record_not_found", 404))
    }

    suspend fun cancelCashRelation(id: String) {
        relations.removeAll { it.id == id }
    }

    suspend fun dissolveCashRelations(factId: String): CashRecordDetail {
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
        val record = effectiveRecord(findRecord(transferRoot(source.id)))
        val pair = transferMembers(record.id).map(::findRecord)
        val allMembers = projectionMembers(record.id)
        val accepted = auditRelations.filter { it.active && it.state == "confirmed" && it.members.any(allMembers::contains) }
        val pending = allMembers.any { importedStates[it] == "draft" || pendingRelations(it) }
        val transfer = if (pair.size == 2) CashTransfer(accountById(pair[0].accountId)!!, pair[0].amount, pair[0].currency,
            accountById(pair[1].accountId)!!, pair[1].amount, pair[1].currency) else null
        val refunds = accepted.filter { it.kind == "refund_offset" && it.members.first() == record.id }
        val netAmount = if (refunds.isNotEmpty() && record.amount.startsWith('-')) {
            val remaining = allocationBalance(record.amount, refunds.map { findRecord(it.members.last()).amount.removePrefix("-") } + "0").difference
            if (compareDemoDecimals(remaining, "0") == 0) "0.00" else "-" + remaining
        } else null
        val imported = importedFields[record.id]
        fun text(key: String, fallback: String): String? = if (imported == null) fallback else (imported.fields[key] as? StructuredText)?.value
        return CashProjection(
        projectionId = "projection-${record.id}",
        occurredAt = text("occurred_at", record.occurredAt),
        account = accountById(record.accountId),
        counterparty = record.counterparty,
        category = categoryById(record.categoryId),
        note = record.note,
        amount = netAmount ?: text("amount", record.amount),
        currency = text("currency", record.currency),
        economicType = if (transfer != null) "internal_transfer" else if (record.recordType == "transfer") "transfer" else if (record.recordType == "income") "income" else "expense",
        businessLabel = auditLabels[record.id] ?: record.recordSubtype.takeIf { transfer != null || record.recordType == "transfer" },
        transfer = transfer,
        memberCount = allMembers.size,
        state = importedStates[record.id] ?: "posted",
        confirmationStatus = if (pending) "pending" else "confirmed",
        attentionCount = if (pending) 1 else 0,
        statisticsEligible = importedStates[record.id] != "draft" && record.recordType != "refund",
        composition = (if (transfer != null) listOf("transfer_pair") else accepted.map { it.kind }).distinct(),
        recordId = record.id,
        transactionId = record.id,
        sourceType = record.sourceType,
        sourceTypes = allMembers.mapNotNull { findRecord(it).sourceType }.distinct(),
        recordType = text("record_type", record.recordType),
        recordSubtype = text("record_subtype", record.recordSubtype),
        attentionCodes = if (allMembers.any(::pendingRelations)) listOf("relation_pending") else emptyList(),
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
        id = record.id,
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
        val minimum = filters.amountMin?.let { projected.amount?.let { amount -> compareDemoDecimals(amount, it) >= 0 } ?: false } ?: true
        val maximum = filters.amountMax?.let { projected.amount?.let { amount -> compareDemoDecimals(amount, it) <= 0 } ?: false } ?: true
        return minimum && maximum &&
            (filters.accountId == null || record.accountId?.toString() == filters.accountId) &&
            (filters.counterparty.isNullOrBlank() || record.counterparty.contains(filters.counterparty, true)) &&
            (filters.categoryId == null || record.categoryId == filters.categoryId) &&
            (filters.uncategorized != "true" || record.categoryId == null) &&
            (filters.currency == null || record.currency == filters.currency) &&
            (filters.dateFrom == null || projected.occurredAt?.let { getLocalDateForInstant(it) >= filters.dateFrom } == true) &&
            (filters.dateTo == null || projected.occurredAt?.let { getLocalDateForInstant(it) <= filters.dateTo } == true) &&
            (filters.economicType == null || projected.economicType == filters.economicType) &&
            (filters.businessLabel == null || projected.businessLabel == filters.businessLabel) &&
            (when (filters.composition) {
                null -> true
                "single" -> projected.composition.isEmpty()
                "combined" -> projected.composition.distinct().size > 1
                else -> filters.composition in projected.composition
            }) &&
            (filters.state == null || projected.state == filters.state) &&
            (filters.confirmationStatus == null || projected.confirmationStatus == filters.confirmationStatus)
    }

    private fun findRecord(id: String): CashRecord = records.firstOrNull { it.id == id || "fact-${it.id}" == id }
        ?: throw demoFailure("cash_record_not_found", 404)

    private fun categoryById(id: String?): CashCategory? = categories.firstOrNull { it.id == id }
    private fun accountById(id: Long?): Account? = accounts.firstOrNull { it.id == id }

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

internal fun compareDemoDecimals(first: String, second: String): Int {
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
