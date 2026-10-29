package com.finance.tracker.backend

import com.finance.tracker.business.domain.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

class CategoryFailure(val code: String) : RuntimeException(code)

class CategoryService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val beforeDelete: () -> Unit = {},private val beforeWorkspaceLock: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private val category = CASH_CATEGORIES
    private val categoryState = CASH_CATEGORY_STATES
    private data class Row(val id: String, var parentId: String?, var name: String, var normalized: String,
        var description: String?, var path: String, var depth: Int, var order: Int, var revision: Long)
    private data class Directory(val workspace: String, var revision: Long, val rows: MutableList<Row>)

    fun list(token: String?): CategoryDirectory = reads.categories(token)

    private fun <T> write(token: String?, expectedRevision: Long?, revisionInput: JsonElement? = null, loadRows: Boolean = true, revisionRepresentation: String? = null,
        action: (Connection, Directory) -> T): T = database.write { connection ->
        val initial = reads.session(connection, token)
        val workspace = initial.workspaceId ?: throw AccessFailure(true)
        beforeWorkspaceLock()
        val exists = connection.lockWorkspace(database.sqlite, workspace)
        if (!exists) throw AccessFailure(true)
        if (reads.session(connection, token, lock = true).workspaceId != workspace) throw AccessFailure(true)
        reads.context(connection, token, true)
        val sql = connection.jooq(database.sqlite)
        val stateQuery = sql.select(categoryState.REVISION).from(categoryState).where(categoryState.WORKSPACE_ID.eq(workspace))
        val revision = (if (database.sqlite) stateQuery else stateQuery.forUpdate()).fetchOne(categoryState.REVISION) ?: run {
            sql.insertInto(categoryState).set(categoryState.WORKSPACE_ID, workspace).set(categoryState.REVISION, 0L).set(categoryState.UPDATED_AT, now()).execute()
            0L
        }
        if (!categoryRevisionMatches(revisionInput, expectedRevision, revision,revisionRepresentation)) throw CategoryFailure("category.revision_conflict")
        val rows = if (!loadRows) mutableListOf<Row>() else sql.select(category.ID, category.PARENT_ID, category.NAME, category.NORMALIZED_NAME,
            category.DESCRIPTION, category.CATEGORY_PATH, category.DEPTH, category.SORT_ORDER, category.REVISION).from(category)
            .where(category.WORKSPACE_ID.eq(workspace)).fetch().map { row -> Row(row[category.ID]!!, row[category.PARENT_ID], row[category.NAME]!!,
                row[category.NORMALIZED_NAME]!!, row[category.DESCRIPTION], row[category.CATEGORY_PATH]!!, row[category.DEPTH]!!.toInt(),
                row[category.SORT_ORDER]!!.toInt(), row[category.REVISION]!!) }.toMutableList()
        action(connection, Directory(workspace, revision, rows))
    }

    private fun name(value: String): String = value.split(Regex("[${pythonWhitespace.joinToString("") { "\\u" + it.toString(16).padStart(4, '0') }}]+"))
        .filter { it.isNotEmpty() }.joinToString(" ").also {
            if (it.isEmpty() || it.codePointCount(0, it.length) > 40) throw CategoryFailure("category.invalid_name")
            strictUtf8(it)
        }

    private fun fold(value: String): String = buildString {
        value.codePoints().forEach { code -> append(pythonCaseFold[code] ?: String(Character.toChars(code))) }
    }

    private fun description(value: String?): String? = value?.trim { it.code in pythonWhitespace }?.takeIf { it.isNotEmpty() }?.also {
        if (it.codePointCount(0, it.length) > 500) throw CategoryFailure("category.invalid_description")
        strictUtf8(it)
    }

    private fun find(directory: Directory, id: String): Row = directory.rows.firstOrNull { it.id == id }
        ?: throw CategoryFailure("category.not_found")

    private fun duplicate(directory: Directory, parentId: String?, normalized: String, except: String? = null) {
        if (directory.rows.any { it.parentId == parentId && it.normalized == normalized && it.id != except }) throw CategoryFailure("category.duplicate_name")
    }

    private fun nextOrder(directory: Directory, parentId: String?): Int = (directory.rows.filter { it.parentId == parentId }.maxOfOrNull { it.order } ?: 0) + 1

    private fun item(directory: Directory, row: Row): CategoryItem {
        val nodes = directory.rows.map { CategoryNode(it.id, it.parentId, it.name, it.order) }.associateBy { it.id }
        return CategoryItem(row.id, row.parentId, row.name, row.description, categoryNamePath(nodes.getValue(row.id), nodes),
            row.depth, row.order, directory.revision)
    }

    private fun bump(connection: Connection, directory: Directory) {
        check(connection.jooq(database.sqlite).update(categoryState).set(categoryState.REVISION, Math.addExact(directory.revision,1))
            .set(categoryState.UPDATED_AT, now()).where(categoryState.WORKSPACE_ID.eq(directory.workspace)).execute() == 1)
        directory.revision=Math.addExact(directory.revision,1)
    }

    private fun persist(connection: Connection, directory: Directory, row: Row) {
        check(connection.jooq(database.sqlite).update(category).set(category.PARENT_ID, row.parentId).set(category.PARENT_SCOPE_KEY, row.parentId ?: "__root__")
            .set(category.NAME, row.name).set(category.NORMALIZED_NAME, row.normalized).set(category.DESCRIPTION, row.description)
            .set(category.CATEGORY_PATH, row.path).set(category.DEPTH, row.depth.toLong()).set(category.SORT_ORDER, row.order.toLong())
            .set(category.REVISION, row.revision).set(category.UPDATED_AT, now()).where(category.WORKSPACE_ID.eq(directory.workspace)).and(category.ID.eq(row.id)).execute() == 1)
    }

    fun create(token: String?, name: String, parentId: String? = null, description: String? = null,
        expectedRevision: Long? = null, revisionInput: JsonElement? = null): CategoryItem {
        val normalizedName = name(name)
        val normalized = fold(normalizedName)
        val detail = description(description)
        return write(token, expectedRevision, revisionInput) { connection, directory ->
            val parent = parentId?.takeIf { it.isNotEmpty() }?.let { find(directory, it) }
            val depth = (parent?.depth ?: 0) + 1
            if (depth > 5) throw CategoryFailure("category.depth_limit")
            duplicate(directory, parent?.id, normalized)
            val id = newId()
            val row = Row(id, parent?.id, normalizedName, normalized, detail, "${parent?.path ?: ""}/$id/", depth, nextOrder(directory, parent?.id), 1)
            connection.jooq(database.sqlite).insertInto(category).set(category.ID, id).set(category.WORKSPACE_ID, directory.workspace)
                .set(category.PARENT_ID, row.parentId).set(category.PARENT_SCOPE_KEY, row.parentId ?: "__root__").set(category.NAME, row.name)
                .set(category.NORMALIZED_NAME, row.normalized).set(category.DESCRIPTION, row.description).set(category.CATEGORY_PATH, row.path)
                .set(category.DEPTH, row.depth.toLong()).set(category.SORT_ORDER, row.order.toLong()).set(category.REVISION, row.revision)
                .set(category.CREATED_AT, now()).set(category.UPDATED_AT, now()).execute()
            directory.rows.add(row)
            bump(connection, directory)
            item(directory, row)
        }
    }

    private fun move(connection: Connection, directory: Directory, row: Row, parentId: String?): Set<Long> {
        val parent = parentId?.takeIf { it.isNotEmpty() }?.let { find(directory, it) }
        val nodes = directory.rows.map { CategoryNode(it.id, it.parentId, it.name, it.order) }.associateBy { it.id }
        val positions = directory.rows.map { CategoryPosition(it.id, it.depth, categoryAncestors(nodes.getValue(it.id), nodes).map { ancestor -> ancestor.id }) }
        val plan = categoryMovePlan(positions, row.id, parent?.id)
        if (plan.violation == "category.cycle") throw CategoryFailure("category.cycle")
        duplicate(directory, parent?.id, row.normalized, row.id)
        plan.violation?.let { throw CategoryFailure(it) }
        val affected=buildSet {
            for(batch in plan.depths.keys.chunked(400)) {
                val sql = connection.jooq(database.sqlite); val t = CASH_TRANSACTIONS; val e = CASH_ENTRIES
                addAll(sql.select(t.ID).from(t).where(t.WORKSPACE_ID.eq(directory.workspace)).and(t.CATEGORY_ID.`in`(batch))
                    .and(t.DELETED_AT.isNull).fetch(t.ID).filterNotNull())
                addAll(sql.select(e.ROOT_TRANSACTION_ID).from(e).where(e.WORKSPACE_ID.eq(directory.workspace)).and(e.CATEGORY_ID.`in`(batch))
                    .fetch(e.ROOT_TRANSACTION_ID).filterNotNull())
            }
        }
        val oldPath = row.path
        val newPath = "${parent?.path ?: ""}/${row.id}/"
        row.parentId = parent?.id
        row.order = nextOrder(directory, parent?.id)
        directory.rows.filter { it.id in plan.depths }.forEach { child ->
            child.path = newPath + child.path.removePrefix(oldPath)
            child.depth = plan.depths.getValue(child.id)
            persist(connection, directory, child)
        }
        bump(connection, directory)
        return affected
    }

    fun move(token: String?, categoryId: String, parentId: String?, expectedRevision: Long?): CategoryItem =
        write(token, expectedRevision) { connection, directory ->
            val row = find(directory, categoryId)
            val affected=move(connection, directory, row, parentId)
            if(affected.isNotEmpty()) CashProjectionService.maintainIfReadyInSession(CashSqlContext(connection,directory.workspace,database.sqlite,now),affected)
            item(directory, row)
        }

    fun update(token: String?, categoryId: String, name: String? = null, description: String? = null,
        parentId: String? = null, changeParent: Boolean = false, parentComparison: String? = parentId,
        expectedRevision: Long? = null, revisionInput: JsonElement? = null): CategoryItem =
        write(token, expectedRevision, revisionInput) { connection, directory ->
            val row = find(directory, categoryId)
            val moved = changeParent && parentComparison != row.parentId
            if (moved) move(connection, directory, row, parentId)
            if (name != null) {
                val normalized = name(name)
                val folded = fold(normalized)
                duplicate(directory, row.parentId, folded, row.id)
                row.name = normalized; row.normalized = folded
            }
            if (description != null) row.description = description(description)
            row.revision=Math.addExact(row.revision,1)
            persist(connection, directory, row)
            if (!moved) bump(connection, directory)
            item(directory, row)
        }

    fun deletionImpact(token: String?,categoryId: String): JsonObject = database.read { connection ->
        val context=reads.context(connection,token,false)
        val workspace=context.workspaceId!!
        val impact=CategoryDeletionRepository(database.sqlite).impact(connection,workspace,categoryId)
        val revision=connection.jooq(database.sqlite).select(categoryState.REVISION).from(categoryState)
            .where(categoryState.WORKSPACE_ID.eq(workspace)).fetchOne(categoryState.REVISION) ?: 0L
        if(reads.context(connection,token,false).workspaceId!=workspace) throw AccessFailure(true)
        buildJsonObject {put("category_id",categoryId);put("revision",revision);put("category_revision",impact.rowRevision)
            put("child_count",impact.children);put("direct_usage_count",impact.usage)}
    }

    fun delete(token: String?,categoryId: String,revisionInput: JsonElement?,categoryRevision: JsonElement?,usageCount: JsonElement?,confirmed: Boolean,integerRepresentations: Map<String,String> = emptyMap()): JsonObject {
        beforeDelete()
        return write(token,null,revisionInput,loadRows=false,revisionRepresentation=integerRepresentations["expected_revision"]) {connection,directory ->
            val repository=CategoryDeletionRepository(database.sqlite)
            val impact=repository.impact(connection,directory.workspace,categoryId,lock=true)
            if(impact.children!=0L) throw CategoryFailure("category.has_children")
            fun matches(value:JsonElement?,actual:Long,field:String):Boolean {
                check(value!=null && value!=JsonNull) {"category impact is not integer-compatible"}
                return categoryRevisionMatches(value,null,actual,integerRepresentations[field])
            }
            if(!matches(categoryRevision,impact.rowRevision,"expected_category_revision") || !matches(usageCount,impact.usage,"expected_usage_count")) throw CategoryFailure("category.impact_changed")
            if(impact.usage!=0L && !confirmed) throw CategoryFailure("category.delete_confirmation_required")
            repository.clear(connection,directory.workspace,categoryId,now(),impact.usage)
            check(connection.jooq(database.sqlite).deleteFrom(category).where(category.WORKSPACE_ID.eq(directory.workspace)).and(category.ID.eq(categoryId)).execute()==1)
            bump(connection,directory)
            buildJsonObject {put("category_id",categoryId);put("cleared_transaction_count",impact.usage);put("revision",directory.revision)}
        }

    }

    fun reorder(token: String?, categoryId: String, direction: String, expectedRevision: Long? = null,
        revisionInput: JsonElement? = null): CategoryItem {
        if (direction !in setOf("before", "after")) throw CategoryFailure("category.invalid_order")
        return write(token, expectedRevision, revisionInput) { connection, directory ->
            val row = find(directory, categoryId)
            val siblings = directory.rows.filter { it.parentId == row.parentId }.sortedWith(compareBy<Row> { it.order }.thenBy { it.id })
            val index = siblings.indexOf(row)
            val otherIndex = index + if (direction == "before") -1 else 1
            if (otherIndex in siblings.indices) {
                val other = siblings[otherIndex]
                val previous = row.order; row.order = other.order; other.order = previous
                if (other.order != row.order) check(connection.jooq(database.sqlite).update(category).set(category.SORT_ORDER, other.order.toLong())
                    .set(category.UPDATED_AT, now()).where(category.WORKSPACE_ID.eq(directory.workspace)).and(category.ID.eq(other.id)).execute() == 1)
                row.revision=Math.addExact(row.revision,1)
                persist(connection, directory, row)
                bump(connection, directory)
            }
            item(directory, row)
        }
    }
}
