"""Import-safe ledger schema constants.

This module must stay free of environment-dependent path resolution so
application services and adapters can be imported without touching the user
ledger.
"""

# Known display codes only; validation uses normalize_currency (open 3-letter).
CURRENCIES = ("CNY", "USD", "HKD")
CURRENCY_SYMBOLS = {
    "CNY": "¥",
    "USD": "$",
    "HKD": "HK$",
}

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

CATEGORIES = ("income", "expense", "transfer", "transfer_in", "transfer_out", "checkin")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "transfer_in": "转入",
    "transfer_out": "转出",
    "checkin": "校准",
}

SOURCE_LABELS = {
    "alipay": "支付宝",
    "wechat": "微信",
    "icbc_credit": "工行信用卡",
    "icbc_debit": "工行借记卡",
}

FOREIGN_EXCHANGE_KEYWORDS = ("购汇", "跨境", "外汇", "换汇")

CASH_CSV_FIELDS = [
    "record_id", "occurred_at", "amount", "currency", "counterparty",
    "note", "category", "record_type", "account_name", "source_type",
]

CSV_FIELDS = [
    "date", "action", "from_ticker", "to_ticker",
    "from_amount", "to_amount", "commission",
    "commission_asset", "currency", "account_name", "note",
]

VALID_ACTIONS = {"swap", "deposit", "withdraw", "dividend", "checkin"}

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

DEFAULT_ACCOUNTS_YAML = """\
accounts:
  - name: 支付宝余额
    type: cash
    currency: CNY
    active: true
  - name: 微信零钱
    type: cash
    currency: CNY
    active: true
  - name: 工行借记卡
    type: cash
    currency: CNY
    active: true
  - name: 工行信用卡(1200)
    type: loan
    currency: CNY
    active: true
"""

DEFAULT_SNAPSHOT = {
    "updated_at": "",
    "accounts": {
        "cash": {},
        "loan": {},
        "lend": {},
        "security": {},
    },
}
