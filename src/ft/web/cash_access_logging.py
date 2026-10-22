"""按键结果查询的访问日志只保留路由，不记录用户幂等键。"""
import logging
import re


_KEY_PATH = re.compile(r"/api/v1/cash-mutations/receipts/by-key/[^\s\"']+")


def _redact(value):
    return _KEY_PATH.sub("/api/v1/cash-mutations/receipts/by-key/[redacted]", value) if isinstance(value, str) else value


class CashReceiptAccessFilter(logging.Filter):
    def filter(self, record):
        record.msg = _redact(record.msg)
        if isinstance(record.args, tuple):
            record.args = tuple(_redact(item) for item in record.args)
        elif isinstance(record.args, dict):
            record.args = {key: _redact(value) for key, value in record.args.items()}
        return True


def install_cash_access_filter():
    logger = logging.getLogger("uvicorn.access")
    if not any(isinstance(item, CashReceiptAccessFilter) for item in logger.filters):
        logger.addFilter(CashReceiptAccessFilter())
