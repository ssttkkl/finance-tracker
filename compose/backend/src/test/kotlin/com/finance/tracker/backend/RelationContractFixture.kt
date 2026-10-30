package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

/** 关系仓库/服务契约测试共用的固定账本数据。 */
internal object RelationContractFixture {
    val workspace = "relation-workspace"
    val now: Instant = Instant.parse("2026-10-01T09:00:00Z")

    private fun <T> Path.usePath(block: (String) -> T): T = block(toUri().rawPath)

    fun seed(database: Database, jdbcUrl: String) {
        val sqlite = database.sqlite
        val url = if (sqlite) "jdbc:sqlite:file:${Path.of(jdbcUrl.removePrefix("jdbc:sqlite:"))
            .toUri().rawPath}?mode=rw" else jdbcUrl
        java.sql.DriverManager.getConnection(url, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")).use { connection ->
            if (sqlite) connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            val sql = connection.jooq(sqlite)
            sql.deleteFrom(CASH_RELATION_CANDIDATES).where(CASH_RELATION_CANDIDATES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTION_COMPONENTS).where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(ACCOUNT_ALIASES).where(ACCOUNT_ALIASES.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute()
            sql.deleteFrom(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).execute()
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, workspace).set(WORKSPACES.NAME, "关系仓库工作区")
                .set(WORKSPACES.CREATED_AT, now).execute()
            for ((id, name) in listOf(9301L to "现金", 9302L to "工资卡")) {
                sql.insertInto(ACCOUNTS).set(ACCOUNTS.ID, id).set(ACCOUNTS.WORKSPACE_ID, workspace)
                    .set(ACCOUNTS.NAME, name).set(ACCOUNTS.TYPE, "cash").set(ACCOUNTS.ACTIVE, true)
                    .set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY"))))
                    .set(ACCOUNTS.METADATA_JSON, JsonObject(emptyMap())).set(ACCOUNTS.REVISION, 1L)
                    .set(ACCOUNTS.CREATED_AT, now).set(ACCOUNTS.UPDATED_AT, now).execute()
            }
            val rows = listOf(
                listOf(93001L, 9301L, "alipay", "REL-1", "consumption", "-100.00", "atomic", 94001L, "-100.00"),
                listOf(93002L, 9301L, "wechat", "REL-2", "refund", "30.00", "atomic", 94002L, "30.00"),
                listOf(93003L, 9302L, "ccb_debit", "REL-3", "transfer_out", "-50.00", "atomic", 94003L, "-50.00"),
                listOf(93004L, 9302L, "icbc_debit", "REL-4", "transfer_in", "50.00", "atomic", 94004L, "50.00"),
                listOf(93005L, 9301L, "alipay", "REL-5", "consumption", "-20.00", "atomic", 94005L, "-20.00"),
                listOf(93006L, 9302L, "alipay", "REL-6", "consumption", "-20.00", "aggregate", 94006L, "-12.00"),
                listOf(93007L, 9302L, "icbc_debit", "REL-7", "consumption", "-30.00", "atomic", 94008L, "-30.00"))
            for (row in rows) {
                val recordType = row[4] as String
                sql.insertInto(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ID, row[0] as Long)
                    .set(CASH_TRANSACTIONS.WORKSPACE_ID, workspace)
                    .set(CASH_TRANSACTIONS.ACCOUNT_ID,
                        if (row[6] == "aggregate") null else row[1] as Long)
                    .set(CASH_TRANSACTIONS.STATE, "posted").set(CASH_TRANSACTIONS.RECORD_ORIGIN, "import")
                    .set(CASH_TRANSACTIONS.ROW_REVISION, 1L).set(CASH_TRANSACTIONS.CASH_GRANULARITY, row[6] as String)
                    .set(CASH_TRANSACTIONS.SOURCE_TYPE, row[2] as String).set(CASH_TRANSACTIONS.RECORD_ID, row[3] as String)
                    .set(CASH_TRANSACTIONS.SOURCE_PAYLOAD, if (row[0] == 93001L) JsonObject(mapOf(
                        "订单号" to JsonPrimitive("REL-1"), "交易状态" to JsonPrimitive("交易成功"),
                        "交易单号" to JsonPrimitive("TXN-1"))) else JsonObject(emptyMap()))
                    .set(CASH_TRANSACTIONS.OCCURRED_AT, now).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal(row[5] as String))
                    .set(CASH_TRANSACTIONS.CURRENCY, "CNY").set(CASH_TRANSACTIONS.COUNTERPARTY, "商户")
                    .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT, "")
                    .set(CASH_TRANSACTIONS.COUNTERPARTY_ACCOUNT_ATTRS, JsonArray(emptyList()))
                    .set(CASH_TRANSACTIONS.NOTE, "").setNull(CASH_TRANSACTIONS.CATEGORY_ID)
                    .set(CASH_TRANSACTIONS.RECORD_TYPE, recordType)
                    .set(CASH_TRANSACTIONS.RECORD_SUBTYPE,
                        if (recordType.startsWith("transfer_")) "ordinary_transfer" else "not_applicable")
                    .set(CASH_TRANSACTIONS.CREATED_AT, now).execute()
            }
            for (row in rows) {
                sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ID, row[7] as Long)
                    .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
                    .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, row[0] as Long)
                    .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, 9301L)
                    .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal(row[8] as String))
                    .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, "CNY").set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 0L)
                    .set(CASH_TRANSACTION_COMPONENTS.LABEL, "").set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
                    .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
                    .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now).execute()
            }
            sql.insertInto(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.ID, 94007L)
                .set(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID, workspace)
                .set(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID, 93006L)
                .set(CASH_TRANSACTION_COMPONENTS.ACCOUNT_ID, 9301L)
                .set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal("-8.00"))
                .set(CASH_TRANSACTION_COMPONENTS.CURRENCY, "CNY").set(CASH_TRANSACTION_COMPONENTS.ORDINAL, 1L)
                .set(CASH_TRANSACTION_COMPONENTS.LABEL, "").set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "")
                .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, JsonObject(emptyMap()))
                .set(CASH_TRANSACTION_COMPONENTS.CREATED_AT, now).execute()
            sql.insertInto(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ID, 9001L)
                .set(TRANSACTION_RELATIONS.WORKSPACE_ID, workspace).set(TRANSACTION_RELATIONS.KIND, "payment_mirror")
                .set(TRANSACTION_RELATIONS.SUBTYPE, "")
                .set(TRANSACTION_RELATIONS.PRIMARY_TRANSACTION_ID, 93001L)
                .set(TRANSACTION_RELATIONS.SECONDARY_TRANSACTION_ID, 93005L)
                .set(TRANSACTION_RELATIONS.PRIMARY_COMPONENT_ID, 94001L)
                .set(TRANSACTION_RELATIONS.SECONDARY_COMPONENT_ID, 94005L)
                .set(TRANSACTION_RELATIONS.ANCHOR_TRANSACTION_ID, 93001L)
                .set(TRANSACTION_RELATIONS.ANCHOR_COMPONENT_ID, 94001L)
                .set(TRANSACTION_RELATIONS.PRIMARY_ROLE, "expense").set(TRANSACTION_RELATIONS.SECONDARY_ROLE, "expense")
                .set(TRANSACTION_RELATIONS.STATE, "confirmed").set(TRANSACTION_RELATIONS.ACTIVE, true)
                .set(TRANSACTION_RELATIONS.ROW_REVISION, 1L).setNull(TRANSACTION_RELATIONS.DECISION_GROUP_ID)
                .set(TRANSACTION_RELATIONS.DECISION_SOURCE, "rule")
                .set(TRANSACTION_RELATIONS.EVIDENCE, JsonObject(emptyMap()))
                .set(TRANSACTION_RELATIONS.APPLIED_AMOUNT, CashStoredDecimal("20.00"))
                .set(TRANSACTION_RELATIONS.RULE_ID, "mirror.seed.v1")
                .set(TRANSACTION_RELATIONS.CREATED_BY, "system").set(TRANSACTION_RELATIONS.CREATED_AT, now)
                .set(TRANSACTION_RELATIONS.DECIDED_BY, "").setNull(TRANSACTION_RELATIONS.DECIDED_AT)
                .set(TRANSACTION_RELATIONS.DECISION_REASON, "").execute()
            sql.insertInto(CASH_RELATION_CANDIDATES)
                .set(CASH_RELATION_CANDIDATES.WORKSPACE_ID, workspace)
                .set(CASH_RELATION_CANDIDATES.RELATION_ID, 9001L)
                .set(CASH_RELATION_CANDIDATES.CANDIDATE_TRANSACTION_ID, 93007L)
                .set(CASH_RELATION_CANDIDATES.CANDIDATE_COMPONENT_ID, 94008L)
                .set(CASH_RELATION_CANDIDATES.EXPECTED_ROW_REVISION, 1L)
                .set(CASH_RELATION_CANDIDATES.EVIDENCE, JsonObject(emptyMap()))
                .set(CASH_RELATION_CANDIDATES.VALIDITY, "valid").execute()
            for ((aliasType, aliasValue, accountId) in listOf(
                Triple("card_tail", "1234", 9301L), Triple("account_identifier", "622202123456", 9302L))) {
                sql.insertInto(ACCOUNT_ALIASES).set(ACCOUNT_ALIASES.WORKSPACE_ID, workspace)
                    .set(ACCOUNT_ALIASES.ALIAS_TYPE, aliasType).set(ACCOUNT_ALIASES.ALIAS_VALUE, aliasValue)
                    .set(ACCOUNT_ALIASES.ACCOUNT_ID, accountId).set(ACCOUNT_ALIASES.CREATED_AT, now)
                    .set(ACCOUNT_ALIASES.UPDATED_AT, now).execute()
            }
        }
    }
}
