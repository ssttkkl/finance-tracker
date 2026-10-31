package com.finance.tracker.backend.persistence

import com.finance.tracker.backend.CashStoredDecimal
import java.math.BigDecimal
import java.sql.Connection
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.sqlite.Function

internal fun registerAssociationDecimalComparison(connection: Connection) {
    Function.create(connection, "ft_association_decimal_compare", object : Function() {
        override fun xFunc() {
            val left = value_text(0); val right = value_text(1)
            if (left == null || right == null) { result(); return }
            try { result(BigDecimal(left).compareTo(BigDecimal(right))) }
            catch (_: NumberFormatException) { error("invalid decimal comparison") }
        }
    }, 2, Function.FLAG_DETERMINISTIC)
}

internal fun associationMagnitude(sqlite: Boolean, column: Field<CashStoredDecimal?>, value: BigDecimal): Condition {
    if (!sqlite) return column.eq(CashStoredDecimal(value.toPlainString())).or(column.eq(CashStoredDecimal(value.negate().toPlainString())))
    fun matches(number: BigDecimal) = DSL.function("ft_association_decimal_compare", Int::class.java, column, DSL.`val`(number.toPlainString())).eq(0)
    return matches(value).or(matches(value.negate()))
}
