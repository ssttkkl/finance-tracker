package com.finance.tracker.backend.persistence

import com.finance.tracker.backend.CashStoredDecimal
import com.finance.tracker.backend.fixedCashDecimal
import com.finance.tracker.backend.storedCashDecimal
import java.math.BigDecimal
import java.sql.Types
import org.jooq.*
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

internal class CashDecimalBinding : Binding<BigDecimal, CashStoredDecimal> {
    override fun converter(): Converter<BigDecimal, CashStoredDecimal> = Converter.ofNullable(
        BigDecimal::class.java, CashStoredDecimal::class.java,
        { CashStoredDecimal(it.toString()) }, { it.text.toBigDecimal() },
    )

    override fun sql(ctx: BindingSQLContext<CashStoredDecimal>) {
        val sqlite = ctx.dialect().family() == SQLDialect.SQLITE
        if (ctx.render().paramType() == ParamType.INLINED) {
            val text = ctx.value()?.let { if (sqlite) fixedCashDecimal(it) else it.text }
            if (!sqlite) ctx.render().sql("cast(")
            ctx.render().visit(DSL.inline(text))
            if (!sqlite) ctx.render().sql(" as numeric)")
        } else {
            if (!sqlite) ctx.render().sql("cast(")
            ctx.render().sql(ctx.variable())
            if (!sqlite) ctx.render().sql(" as numeric)")
        }
    }

    override fun set(ctx: BindingSetStatementContext<CashStoredDecimal>) {
        val value = ctx.value()
        if (ctx.dialect().family() == SQLDialect.SQLITE) ctx.statement().setString(ctx.index(), value?.let(::fixedCashDecimal))
        else if (value == null) ctx.statement().setNull(ctx.index(), Types.NUMERIC)
        else ctx.statement().setString(ctx.index(), value.text)
    }

    override fun get(ctx: BindingGetResultSetContext<CashStoredDecimal>) {
        ctx.value(ctx.resultSet().getString(ctx.index())?.let { storedCashDecimal(it, ctx.dialect().family() == SQLDialect.SQLITE) })
    }

    override fun get(ctx: BindingGetStatementContext<CashStoredDecimal>) {
        ctx.value(ctx.statement().getString(ctx.index())?.let { storedCashDecimal(it, ctx.dialect().family() == SQLDialect.SQLITE) })
    }

    override fun register(ctx: BindingRegisterContext<CashStoredDecimal>) { ctx.statement().registerOutParameter(ctx.index(), Types.NUMERIC) }
    override fun get(ctx: BindingGetSQLInputContext<CashStoredDecimal>) { ctx.value(ctx.input().readString()?.let { storedCashDecimal(it, false) }) }
    override fun set(ctx: BindingSetSQLOutputContext<CashStoredDecimal>) { ctx.output().writeBigDecimal(ctx.value()?.text?.toBigDecimal()) }
}
