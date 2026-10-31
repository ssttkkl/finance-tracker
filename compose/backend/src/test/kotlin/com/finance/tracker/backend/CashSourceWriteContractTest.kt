package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceWriteContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_SOURCE_WRITE_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun state(context: CashSqlContext): JsonElement {
        val sql = context.sql
        val b = CASH_SOURCE_BINDINGS
        val i = CASH_TRANSACTION_ISSUES
        val c = CASH_ISSUE_CANDIDATES
        val e = CASH_SOURCE_EMPTY_PAGES
        val bindings = sql.selectFrom(b).where(b.WORKSPACE_ID.eq(context.workspace).and(b.TRANSACTION_ID.ge(7601)))
            .orderBy(b.TRANSACTION_ID, b.SOURCE_SCOPE_KIND, b.SOURCE_SCOPE_KEY, b.IDENTITY_KIND, b.IDENTITY_KEY).fetch().map { r -> linkedMapOf(
                "transaction_id" to r[b.TRANSACTION_ID], "source_type" to r[b.SOURCE_TYPE], "source_scope_kind" to r[b.SOURCE_SCOPE_KIND],
                "source_scope_key" to r[b.SOURCE_SCOPE_KEY], "identity_kind" to r[b.IDENTITY_KIND], "identity_key" to r[b.IDENTITY_KEY],
                "identity_strength" to r[b.IDENTITY_STRENGTH], "stable_evidence_key" to r[b.STABLE_EVIDENCE_KEY],
                "strategy_version" to r[b.STRATEGY_VERSION], "coverage" to cashSourceValue(r[b.COVERAGE]), "revision" to r[b.REVISION]) }
        val issues = sql.selectFrom(i).where(i.WORKSPACE_ID.eq(context.workspace).and(i.TRANSACTION_ID.ge(7601)))
            .orderBy(i.TRANSACTION_ID, i.ISSUE_KEY).fetch().map { r -> linkedMapOf(
                "transaction_id" to r[i.TRANSACTION_ID], "issue_key" to r[i.ISSUE_KEY], "code" to r[i.CODE], "field_path" to r[i.FIELD_PATH],
                "pending_source_payload" to cashSourceValue(r[i.PENDING_SOURCE_PAYLOAD]), "pending_fields" to cashSourceValue(r[i.PENDING_FIELDS]),
                "origin" to cashSourceValue(r[i.ORIGIN]), "revision" to r[i.REVISION], "candidates" to sql.selectFrom(c)
                    .where(c.WORKSPACE_ID.eq(context.workspace).and(c.ISSUE_ID.eq(r[i.ID])))
                    .orderBy(c.CANDIDATE_TRANSACTION_ID).fetch().map { listOf(it[c.CANDIDATE_TRANSACTION_ID], it[c.EXPECTED_ROW_REVISION]) }) }
        val pages = sql.selectFrom(e).where(e.WORKSPACE_ID.eq(context.workspace))
            .orderBy(e.SOURCE_TYPE, e.PARSER_VERSION, e.DOCUMENT_KEY, e.PAGE_NUMBER).fetch().map { r -> linkedMapOf(
                "source_type" to r[e.SOURCE_TYPE], "parser_version" to r[e.PARSER_VERSION], "document_key" to r[e.DOCUMENT_KEY],
                "page_number" to r[e.PAGE_NUMBER], "coverage" to cashSourceValue(r[e.COVERAGE])) }
        return sourceJson(mapOf("bindings" to bindings, "issues" to issues, "pages" to pages))
    }
    private fun files(value: JsonElement): List<ParsedImportFile> {
        val stored = Json.parseToJsonElement(root().resolve("input-session.json").readText()).jsonObject
        val content = JsonObject(stored.getValue("content").jsonObject + ("files" to value))
        return ParsedImportCodec().decode(stored.getValue("session_id").jsonPrimitive.content, content.toString().encodeToByteArray()).files
    }
    private fun row(value: JsonObject): SourceOccurrence {
        fun text(key: String) = value[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        @Suppress("UNCHECKED_CAST") val fields = cashSourceValue(value.getValue("fields")) as Map<String, Any?>
        return SourceOccurrence(text("source_type")!!, text("source_account_key"), text("file_digest")!!,
            text("parser_version")!!, text("table_key")!!, value.getValue("position").jsonPrimitive.int,
            fields, cashSourceCoverage(value.getValue("coverage")), text("provider_id"), text("stable_evidence"), text("document_position"))
    }

    @Test fun transactionalSourceWritesMatchPythonAndRollBack() {
        val db = database()
        val before = detailDatabaseState(db)
        for (raw in Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray) {
            val case = raw.jsonObject
            db.write { connection ->
                val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
                val savepoint = connection.setSavepoint()
                val repository = CashSourceWriteRepository(context)
                var error: String? = null
                try {
                    for (item in case.getValue("actions").jsonArray) {
                        val action = item.jsonObject
                        val target = cashSourceValue(action["target"])
                        when (action.getValue("action").jsonPrimitive.content) {
                            "bind" -> repository.saveOccurrence(target, row(action.getValue("row").jsonObject),
                                action["uncertain"]?.jsonPrimitive?.boolean ?: false, action["first_binding"]?.jsonPrimitive?.boolean ?: false)
                            "issue" -> {
                                @Suppress("UNCHECKED_CAST") val candidates = cashSourceValue(action["candidates"]) as? Map<*, *>
                                repository.saveIssue(target, cashSourceValue(action["issue_key"]), cashSourceValue(action["code"]),
                                    action["field_path"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                                    cashSourceValue(action["pending_source_payload"]), cashSourceValue(action["pending_fields"]),
                                    cashSourceValue(action["origin"]), candidates)
                            }
                            "clear" -> repository.clearIssue(target, action.getValue("issue_key").jsonPrimitive.content)
                            "empty" -> CashSourceDocumentWriteRepository(context).saveEmptyPages(files(action.getValue("files")))
                            "parent" -> {
                                val table = CASH_TRANSACTIONS
                                for ((key, value) in action.getValue("changes").jsonObject) when (key) {
                                    "record_id" -> context.sql.update(table).set(table.RECORD_ID, value.jsonPrimitive.content).where(table.ID.eq(target.toString().toLong())).execute()
                                    "source_payload" -> context.sql.update(table).set(table.SOURCE_PAYLOAD, value).where(table.ID.eq(target.toString().toLong())).execute()
                                    "row_revision" -> context.sql.update(table).set(table.ROW_REVISION, value.jsonPrimitive.long).where(table.ID.eq(target.toString().toLong())).execute()
                                    "deleted_at" -> context.sql.update(table).set(table.DELETED_AT, Instant.parse(value.jsonPrimitive.content)).where(table.ID.eq(target.toString().toLong())).execute()
                                }
                            }
                        }
                    }
                } catch (failure: CashSourceBindingConflict) { error = failure.code }
                assertEquals(case["error"]?.jsonPrimitive?.content, error, case.getValue("name").jsonPrimitive.content)
                assertEquals(case.getValue("expected"), state(context), case.getValue("name").jsonPrimitive.content)
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun identicalWritesKeepRevisionsAndClearedIssuesNeverReuseIdentity() {
        val db = database()
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            var now = Instant.parse("2026-10-01T09:00:00Z")
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { now })
            val source = CashSourceWriteRepository(context)
            val row = SourceOccurrence("alipay", "owner", "a".repeat(64), "fixture-v1", "table", 1, emptyMap(), providerId = "P1")
            source.saveOccurrence(7601, row)
            val b = CASH_SOURCE_BINDINGS
            val original = context.sql.select(b.REVISION, b.UPDATED_AT).from(b).where(b.TRANSACTION_ID.eq(7601)).fetch().map { it.valuesRow().toString() }
            now = now.plusSeconds(10)
            source.saveOccurrence(7601, row)
            assertEquals(original, context.sql.select(b.REVISION, b.UPDATED_AT).from(b).where(b.TRANSACTION_ID.eq(7601)).fetch().map { it.valuesRow().toString() })
            val first = source.saveIssue(7601, "identity", "source_identity_ambiguous", candidates = mapOf(7602 to 1))
            assertEquals(1, first.revision)
            assertEquals(first, source.saveIssue("07601", "identity", "source_identity_ambiguous", candidates = mapOf("07602" to 1)))
            val changed = source.saveIssue(7601, "identity", "source_identity_ambiguous", pendingFields = mapOf("note" to "new"), candidates = mapOf(7603 to 1))
            assertEquals(first.id, changed.id)
            assertEquals(2, changed.revision)
            val before = state(context)
            assertEquals("source_candidate_changed", assertFailsWith<CashSourceBindingConflict> {
                source.saveIssue(7601, "identity", "source_identity_ambiguous", candidates = mapOf("07601" to 1))
            }.code)
            assertEquals("invalid_source_issue", assertFailsWith<CashSourceBindingConflict> {
                source.saveIssue(7601, "identity", "source_identity_ambiguous", pendingFields = mapOf("raw" to Any()))
            }.code)
            assertEquals(before, state(context))
            source.clearIssue(7601, "identity")
            assertEquals(0, context.sql.fetchCount(CASH_ISSUE_CANDIDATES))
            val recreated = source.saveIssue(7601, "identity", "source_identity_ambiguous")
            assertTrue(recreated.id > first.id)
            assertEquals(1, recreated.revision)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
    }

    @Test fun exceptionsRollBackBindingsIssuesAndEmptyPagesTogether() {
        val db = database()
        val before = db.read { state(CashSqlContext(it, "operation-workspace", db.sqlite)) }
        val cases = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
        val fileValues = cases.single { it.getValue("name").jsonPrimitive.content == "empty_idempotent" }
            .getValue("actions").jsonArray.first().jsonObject.getValue("files")
        val files = files(fileValues)
        class Rollback : RuntimeException()
        assertFailsWith<Rollback> {
            db.write { connection ->
                val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
                val source = CashSourceWriteRepository(context)
                source.saveOccurrence(7601, SourceOccurrence("alipay", "owner", "a".repeat(64), "fixture-v1", "table", 1, emptyMap()))
                source.saveIssue(7601, "identity", "source_identity_ambiguous", candidates = mapOf(7602 to 1))
                val documents = CashSourceDocumentWriteRepository(context)
                documents.saveEmptyPages(files)
                val otherParser = files.map { it.copy(document = it.document!!.copy(parserVersion = "fixture-v2",
                    coverage = it.document.coverage.copy(pageFingerprints = it.document.coverage.pageFingerprints + ("2" to "d".repeat(64))))) }
                documents.saveEmptyPages(otherParser)
                assertEquals(6, context.sql.fetchCount(CASH_SOURCE_EMPTY_PAGES))
                val foreign = CashSourceDocumentWriteRepository(CashSqlContext(connection, "source-foreign", db.sqlite))
                foreign.saveEmptyPages(files)
                assertEquals(9, context.sql.fetchCount(CASH_SOURCE_EMPTY_PAGES))
                throw Rollback()
            }
        }
        assertEquals(before, db.read { state(CashSqlContext(it, "operation-workspace", db.sqlite)) })
        assertEquals(0, db.read { it.jooq(db.sqlite).fetchCount(CASH_SOURCE_EMPTY_PAGES) })
    }
}
