package com.finance.tracker.backend

import com.finance.tracker.business.domain.*
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

class CategoryFailure(val code: String) : RuntimeException(code)

class CategoryService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val beforeDelete: () -> Unit = {},private val beforeWorkspaceLock: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private val lockSuffix get() = if (database.sqlite) "" else " FOR UPDATE"
    private data class Row(val id: String, var parentId: String?, var name: String, var normalized: String,
        var description: String?, var path: String, var depth: Int, var order: Int, var revision: Long)
    private data class Directory(val workspace: String, var revision: Long, val rows: MutableList<Row>)

    fun list(token: String?): CategoryDirectory = reads.categories(token)

    private fun <T> write(token: String?, expectedRevision: Long?, revisionInput: JsonElement? = null, loadRows: Boolean = true, revisionRepresentation: String? = null,
        action: (Connection, Directory) -> T): T = database.write { connection ->
        val initial = reads.session(connection, token)
        val workspace = initial.workspaceId ?: throw AccessFailure(true)
        beforeWorkspaceLock()
        val exists = connection.prepareStatement("SELECT id FROM workspaces WHERE id=?$lockSuffix").use {
            it.setString(1, workspace); it.executeQuery().use { it.next() }
        }
        if (!exists) throw AccessFailure(true)
        if (reads.session(connection, token, lock = true).workspaceId != workspace) throw AccessFailure(true)
        reads.context(connection, token, true)
        val revision = connection.prepareStatement("SELECT revision FROM cash_category_states WHERE workspace_id=?$lockSuffix").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        } ?: run {
            connection.prepareStatement("INSERT INTO cash_category_states(workspace_id,revision,updated_at) VALUES(?,0,?)").use {
                it.setString(1, workspace); it.instant(2, now(), database.sqlite); it.executeUpdate()
            }
            0L
        }
        if (!categoryRevisionMatches(revisionInput, expectedRevision, revision,revisionRepresentation)) throw CategoryFailure("category.revision_conflict")
        val rows = if (!loadRows) mutableListOf<Row>() else connection.prepareStatement("SELECT id,parent_id,name,normalized_name,description,category_path,depth,sort_order,revision FROM cash_categories WHERE workspace_id=?").use {
            it.setString(1, workspace)
            it.executeQuery().use { result -> buildList {
                while (result.next()) add(Row(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                    result.getString(5), result.getString(6), result.getInt(7), result.getInt(8), result.getLong(9)))
            }.toMutableList() }
        }
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
        connection.prepareStatement("UPDATE cash_category_states SET revision=?,updated_at=? WHERE workspace_id=?").use {
            it.setLong(1, Math.addExact(directory.revision,1)); it.instant(2, now(), database.sqlite); it.setString(3, directory.workspace); check(it.executeUpdate() == 1)
        }
        directory.revision=Math.addExact(directory.revision,1)
    }

    private fun persist(connection: Connection, directory: Directory, row: Row) {
        connection.prepareStatement("""UPDATE cash_categories SET parent_id=?,parent_scope_key=?,name=?,normalized_name=?,
            description=?,category_path=?,depth=?,sort_order=?,revision=?,updated_at=? WHERE workspace_id=? AND id=?""").use {
            it.setString(1, row.parentId); it.setString(2, row.parentId ?: "__root__"); it.setString(3, row.name); it.setString(4, row.normalized)
            it.setString(5, row.description); it.setString(6, row.path); it.setInt(7, row.depth); it.setInt(8, row.order); it.setLong(9, row.revision)
            it.instant(10, now(), database.sqlite); it.setString(11, directory.workspace); it.setString(12, row.id); check(it.executeUpdate() == 1)
        }
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
            connection.prepareStatement("""INSERT INTO cash_categories(id,workspace_id,parent_id,parent_scope_key,name,normalized_name,
                description,category_path,depth,sort_order,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""").use {
                it.setString(1, id); it.setString(2, directory.workspace); it.setString(3, row.parentId); it.setString(4, row.parentId ?: "__root__")
                it.setString(5, row.name); it.setString(6, row.normalized); it.setString(7, row.description); it.setString(8, row.path)
                it.setInt(9, row.depth); it.setInt(10, row.order); it.setLong(11, row.revision)
                it.instant(12, now(), database.sqlite); it.instant(13, now(), database.sqlite); it.executeUpdate()
            }
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
                val placeholders=batch.joinToString(",") {"?"}
                for((table,column,filter) in listOf(Triple("cash_transactions","id"," AND deleted_at IS NULL"),Triple("cash_entries","root_transaction_id",""))) {
                    connection.prepareStatement("SELECT $column FROM $table WHERE workspace_id=? AND category_id IN ($placeholders)$filter").use {
                        it.setString(1,directory.workspace);batch.forEachIndexed {index,id ->it.setString(index+2,id)}
                        it.executeQuery().use {result ->while(result.next()) add(result.getLong(1))}
                    }
                }
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
        val revision=connection.prepareStatement("SELECT revision FROM cash_category_states WHERE workspace_id=?").use {
            it.setString(1,workspace);it.executeQuery().use {rows ->if(rows.next()) rows.getLong(1) else 0L}
        }
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
            connection.prepareStatement("DELETE FROM cash_categories WHERE workspace_id=? AND id=?").use {
                it.setString(1,directory.workspace);it.setString(2,categoryId);check(it.executeUpdate()==1)
            }
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
                if (other.order != row.order) connection.prepareStatement("UPDATE cash_categories SET sort_order=?,updated_at=? WHERE workspace_id=? AND id=?").use {
                    it.setInt(1, other.order); it.instant(2, now(), database.sqlite)
                    it.setString(3, directory.workspace); it.setString(4, other.id); check(it.executeUpdate() == 1)
                }
                row.revision=Math.addExact(row.revision,1)
                persist(connection, directory, row)
                bump(connection, directory)
            }
            item(directory, row)
        }
    }
}
