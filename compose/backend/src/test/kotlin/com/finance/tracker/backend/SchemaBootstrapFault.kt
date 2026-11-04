package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean

internal fun failAfterThirdSchemaTable(connection: Connection, reached: AtomicBoolean,
    failure: SQLException, rollbackFailure: SQLException): Connection {
    var tables = 0
    fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        val value = invoke(connection, method, args)
        if (method.name == "rollback") throw rollbackFailure
        if (method.name != "createStatement") value else {
            val statement = value as Statement
            Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, action, parameters ->
                val result = invoke(statement, action, parameters)
                if (action.name == "execute" && parameters?.firstOrNull() == "ROLLBACK") throw rollbackFailure
                if (action.name == "execute" && (parameters?.firstOrNull() as? String)?.startsWith("CREATE TABLE") == true) {
                    tables++
                    if (tables == 3) {
                        reached.set(true)
                        throw failure
                    }
                }
                result
            }
        }
    } as Connection
}

internal fun assertSchemaVersionRejections(database: Database) {
    fun versions(): List<String> = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version_num FROM alembic_version ORDER BY version_num").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }
    fun replace(values: List<String>) = database.write { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM alembic_version") }
        connection.prepareStatement("INSERT INTO alembic_version(version_num) VALUES(?)").use { statement ->
            values.forEach { statement.setString(1, it); statement.executeUpdate() }
        }
    }
    for (invalid in listOf(emptyList(), listOf("20260101_00"), listOf("unknown"), listOf("20261030_53", "other"))) {
        replace(invalid)
        database.bootstrapSchema()
        kotlin.test.assertEquals("storage.schema", kotlin.test.assertFailsWith<StorageFailure> { database.validateSchema() }.code)
        kotlin.test.assertEquals(invalid.sorted(), versions())
    }
    replace(listOf("20261030_53"))
    database.validateSchema()
}
