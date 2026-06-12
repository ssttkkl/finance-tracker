"""数据模型常量"""
from pathlib import Path

# 数据目录
FT_DIR = Path.home() / ".ft"
RECORDS_DIR = FT_DIR / "records"
ACCOUNTS_PATH = FT_DIR / "accounts.yaml"

# 币种
CURRENCIES = ("CNY", "USD", "HKD")
CURRENCY_SYMBOLS = {
    "CNY": "¥",
    "USD": "$",
    "HKD": "HK$",
}

# 账户类型
ACCOUNT_TYPES = ("cash", "loan", "lend", "security")
ACCOUNT_LABELS = {
    "cash": "现金",
    "loan": "贷款",
    "lend": "借款",
    "security": "证券",
}
ACCOUNT_ICONS = {
    "cash": "💰",
    "loan": "💳",
    "lend": "📤",
    "security": "📈",
}

# 交易类型
CATEGORIES = ("income", "expense", "transfer", "checkin")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "checkin": "校准",
}

# 来源
SOURCE_LABELS = {
    "alipay": "支付宝",
    "wechat": "微信",
    "icbc_credit": "工行信用卡",
    "icbc_debit": "工行借记卡",
}

# 购汇/跨境关键词 → 跨币种转账
FOREIGN_EXCHANGE_KEYWORDS = ("购汇", "跨境", "外汇", "换汇")

# CSV 字段（10 列）
CSV_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
