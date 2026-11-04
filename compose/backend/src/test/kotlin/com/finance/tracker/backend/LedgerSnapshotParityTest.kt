package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

/** Identical synthetic rows and complete public JSON expectations for either configured test backend. */
class LedgerSnapshotParityTest {
    private val fixture=Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/ledger-snapshot-parity.json"))
        .bufferedReader().use {it.readText()}).jsonObject
    private val tables=listOf(ACCOUNTS,CASH_CATEGORIES,CASH_CATEGORY_STATES,ACCOUNT_ALIASES,STATEMENT_ACCOUNT_MAPPINGS,
        CASH_TRANSACTIONS,CASH_TRANSACTION_COMPONENTS,CASH_SOURCE_BINDINGS,CASH_SOURCE_EMPTY_PAGES,TRANSACTION_RELATIONS,
        CASH_RELATION_CANDIDATES,CASH_TRANSACTION_ISSUES,CASH_ISSUE_CANDIDATES,CASH_ENTRIES,CASH_ENTRY_MEMBERS,
        INVESTMENT_EVENTS,CASH_INVESTMENT_FUNDING_RELATIONS)
    @Test fun identicalFixtureProducesTheCompleteExpectedSnapshot()=withLedgerTestWorkspace {db,workspace,token ->
        db.write {c ->
            check(c.lockWorkspace(db.sqlite,workspace))
            val sql=c.jooq(db.sqlite)
            for(table in tables) for(element in fixture.getValue("rows").jsonObject.getValue(table.name).jsonArray) {
                val values=element.jsonObject.map { (name,value) ->
                    val field=requireNotNull(table.field(name))
                    val type=field.dataType.type
                    val converted:Any?=when {
                        JsonElement::class.java.isAssignableFrom(type) ->value
                        value==JsonNull ->null
                        type==String::class.java ->value.jsonPrimitive.content
                        type==Long::class.javaObjectType ->value.jsonPrimitive.content.toLong()
                        type==Int::class.javaObjectType ->value.jsonPrimitive.content.toInt()
                        type==Boolean::class.javaObjectType ->value.jsonPrimitive.boolean
                        type==Instant::class.java ->Instant.parse(value.jsonPrimitive.content)
                        type==CashStoredDecimal::class.java ->CashStoredDecimal(value.jsonPrimitive.content)
                        else ->error("Unexpected fixture field type: ${table.name}.$name $type")
                    }
                    field to converted
                }.toMap()+ (requireNotNull(table.field("workspace_id")) to workspace)
                sql.insertInto(table).set(values).execute()
            }
            sql.update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ,9007199254740995L).where(WORKSPACES.ID.eq(workspace)).execute()
        }
        val before=detailDatabaseState(db)
        testApplication {
            application {financeModule(db)}
            val response=client.get("/api/v1/ledger/snapshot") {bearerAuth(token)}
            assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
            val expected=JsonObject(fixture.getValue("expected").jsonObject+("workspace_id" to JsonPrimitive(workspace)))
            assertEquals(expected,Json.parseToJsonElement(response.bodyAsText()))
        }
        assertEquals(before,detailDatabaseState(db),"snapshot must not write financial or session state")
    }
}
