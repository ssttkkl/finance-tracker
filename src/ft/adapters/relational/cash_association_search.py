"""现金部分精确金额筛选的方言适配。"""
from sqlalchemy import case, func, or_


def magnitude_matches(session, column, value):
    if session.bind.dialect.name == "sqlite":
        return or_(*(case((column.is_not(None), func.decimal_compare(column, str(number))), else_=None) == 0
            for number in (value, value.copy_negate())))
    return or_(column == value, column == value.copy_negate())
