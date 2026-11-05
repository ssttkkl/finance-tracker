package com.finance.tracker.backend

import java.sql.Connection
import java.sql.SQLException
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES

internal fun Connection.jooq(sqlite: Boolean): DSLContext {
    System.setProperty("org.jooq.no-logo", "true")
    System.setProperty("org.jooq.no-tips", "true")
    return DSL.using(this, if (sqlite) SQLDialect.SQLITE else SQLDialect.POSTGRES, Settings()
        .withRenderSchema(false).withRenderCatalog(false)
        .withExecuteLogging(false).withExecuteLoggingSQLExceptions(false).withDiagnosticsLogging(false))
}

internal fun Throwable.sqlCause(): SQLException? {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is SQLException) return current
        current = current.cause
    }
    return null
}

internal fun Connection.lockWorkspace(sqlite: Boolean, workspace: String): Boolean {
    val query = jooq(sqlite).select(WORKSPACES.ID).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace))
    return (if (sqlite) query else query.forUpdate()).fetchOne() != null
}
