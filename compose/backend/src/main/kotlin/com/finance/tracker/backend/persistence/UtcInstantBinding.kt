package com.finance.tracker.backend.persistence

import com.finance.tracker.backend.instant
import com.finance.tracker.backend.storedCashInstant
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.jooq.*
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

internal class UtcInstantBinding : Binding<OffsetDateTime, Instant> {
    override fun converter(): Converter<OffsetDateTime, Instant> = Converter.ofNullable(
        OffsetDateTime::class.java, Instant::class.java,
        { it.toInstant() }, { it.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC) },
    )

    override fun sql(ctx: BindingSQLContext<Instant>) {
        if (ctx.render().paramType() == ParamType.INLINED) {
            val sqlite = ctx.dialect().family() == SQLDialect.SQLITE
            val text = ctx.value()?.truncatedTo(ChronoUnit.MICROS)?.let {
                if (sqlite) DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(it)
                else it.atOffset(ZoneOffset.UTC).toString()
            }
            if (!sqlite) ctx.render().sql("cast(")
            ctx.render().visit(DSL.inline(text))
            if (!sqlite) ctx.render().sql(" as timestamp with time zone)")
        } else ctx.render().sql(ctx.variable())
    }

    override fun set(ctx: BindingSetStatementContext<Instant>) {
        val value = ctx.value()
        if (value == null) ctx.statement().setNull(ctx.index(), if (ctx.dialect().family() == SQLDialect.SQLITE) Types.VARCHAR else Types.TIMESTAMP_WITH_TIMEZONE)
        else ctx.statement().instant(ctx.index(), value, ctx.dialect().family() == SQLDialect.SQLITE)
    }

    override fun get(ctx: BindingGetResultSetContext<Instant>) { ctx.value(storedCashInstant(ctx.resultSet().getString(ctx.index()), ctx.dialect().family() == SQLDialect.SQLITE)) }
    override fun get(ctx: BindingGetStatementContext<Instant>) { ctx.value(storedCashInstant(ctx.statement().getString(ctx.index()), ctx.dialect().family() == SQLDialect.SQLITE)) }
    override fun register(ctx: BindingRegisterContext<Instant>) { ctx.statement().registerOutParameter(ctx.index(), Types.TIMESTAMP_WITH_TIMEZONE) }
    override fun get(ctx: BindingGetSQLInputContext<Instant>) { ctx.value(storedCashInstant(ctx.input().readString(), false)) }
    override fun set(ctx: BindingSetSQLOutputContext<Instant>) { ctx.output().writeString(ctx.value()?.truncatedTo(ChronoUnit.MICROS)?.atOffset(ZoneOffset.UTC)?.toString()) }
}
