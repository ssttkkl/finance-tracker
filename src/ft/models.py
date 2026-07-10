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
ACCOUNT_TYPES = ("cash", "loan", "lend", "security", "crypto")
ACCOUNT_LABELS = {
    "cash": "现金",
    "loan": "贷款",
    "lend": "借款",
    "security": "证券",
    "crypto": "加密货币",
}
ACCOUNT_ICONS = {
    "cash": "💰",
    "loan": "💳",
    "lend": "📤",
    "security": "📈",
    "crypto": "🪙",
}

# 交易类型
CATEGORIES = ("income", "expense", "transfer", "transfer_in", "transfer_out", "checkin")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "transfer_in": "转入",
    "transfer_out": "转出",
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

# CSV 字段 — cash records (11 列)
# locked=1 表示该行被人工锁定：reconcile 完全不碰（不去重、不配对、不单腿标记）。
CASH_CSV_FIELDS = ["date", "amount", "currency", "counterparty",
                   "description", "category", "account_name", "source",
                   "bill_source", "transfer_account", "locked"]

# CSV 字段 — security trades (12 列, unified swap)
CSV_FIELDS = [
    "date", "action", "from_ticker", "to_ticker",
    "from_amount", "to_amount", "price", "commission",
    "commission_asset", "currency", "account_name", "note",
]

# Valid actions for security trades
VALID_ACTIONS = {"swap", "deposit", "withdraw", "dividend", "checkin"}

# 加密货币符号 → CoinGecko coin id（新增币种在此补一行）
CRYPTO_IDS = {
    "btc": "bitcoin",
    "eth": "ethereum",
    "usdt": "tether",
    "usdc": "usd-coin",
    "sol": "solana",
    "bnb": "binancecoin",
    "xrp": "ripple",
    "doge": "dogecoin",
    "ada": "cardano",
}
