package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlinx.serialization.json.*

internal fun authOnlyCashParseDatabase(queries: AtomicInteger): Database {
    val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEDGER_CONTRACT_DIR")), "database.json").readText()).jsonObject
    val forbidden = Regex("\\b(cash_[a-z_]+|accounts|account_aliases|statement_account_mappings|transaction_relations|ledger_snapshots|investment_events|record_revisions|insert|update|delete|merge|create|drop|alter)\\b", RegexOption.IGNORE_CASE)
    return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
        System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorateConnection = { connection ->
            Proxy.newProxyInstance(Database::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                if (method.name == "prepareStatement" || method.name == "prepareCall") {
                    val sql = arguments!![0] as String
                    check(!forbidden.containsMatchIn(sql)) { "parse-only touched financial data or write SQL" }
                    queries.incrementAndGet()
                }
                try {
                    val result = method.invoke(connection, *(arguments ?: emptyArray()))
                    if (method.name == "createStatement") Proxy.newProxyInstance(Database::class.java.classLoader,
                        arrayOf(Statement::class.java)) { _, operation, parameters ->
                        if (operation.name.startsWith("execute") || operation.name == "addBatch") {
                            val sql = parameters?.firstOrNull() as? String
                            if (sql != null) {
                                check(!forbidden.containsMatchIn(sql)) { "parse-only touched financial data or write SQL" }
                                queries.incrementAndGet()
                            }
                        }
                        try { operation.invoke(result, *(parameters ?: emptyArray())) }
                        catch (failure: InvocationTargetException) { throw failure.targetException }
                    } else result
                } catch (failure: InvocationTargetException) { throw failure.targetException }
            } as Connection
        })
}
