"""YAML 映射规则解析 + glob 匹配"""
import fnmatch
from pathlib import Path

MAPPING_PATH = Path.home() / ".ft" / "mapping.yaml"

DEFAULT_RULES = """rules:
  - source: alipay
    match: "工商银行信用卡(1200)*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: alipay
    match: "网商银行储蓄卡(4164)*"
    account: "网商储蓄卡(4164)"
    currency: CNY
  - source: alipay
    match: "建设银行储蓄卡(2820)*"
    account: "建行储蓄卡(2820)"
    currency: CNY
  - source: alipay
    match: "工商银行储蓄卡(3697)*"
    account: "工行借记卡"
    currency: CNY
  - source: alipay
    match: "账户余额"
    account: "支付宝余额"
    currency: CNY
  - source: alipay
    match: "余额"
    account: "支付宝余额"
    currency: CNY
  - source: alipay
    match: "花呗*"
    account: "花呗"
    currency: CNY
  - source: alipay
    match: "工商银行信用卡分期(1200)*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: alipay
    match: ""
    account: "支付宝余额"
    currency: CNY
  - source: wechat
    match: "零钱"
    account: "微信零钱"
    currency: CNY
  - source: wechat
    match: "工商银行储蓄卡(3697)*"
    account: "工行借记卡"
    currency: CNY
  - source: wechat
    match: "工商银行信用卡(1200)*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: wechat
    match: "建设银行储蓄卡(2820)*"
    account: "建行储蓄卡(2820)"
    currency: CNY
  - source: wechat
    match: "工商银行信用卡(9166)*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: wechat
    match: "/"
    account: "微信零钱"
    currency: CNY
  - source: wechat
    match: ""
    account: "微信零钱"
    currency: CNY
  - source: icbc_debit
    match: "*"
    account: "工行借记卡"
    currency: CNY
  - source: icbc_credit
    match: "*"
    account: "工行信用卡"
    currency: CNY
  - source: ccb_debit
    match: "*"
    account: "建行储蓄卡"
    currency: CNY

default: skip
"""


def load_rules(path=None) -> tuple[list[dict], str]:
    """加载 mapping.yaml，返回 (rules, default_action)"""
    if path is None:
        path = MAPPING_PATH
    path = Path(path)

    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_RULES, encoding="utf-8")
        print(f"  📝 已创建默认规则: {path}")

    import yaml
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    rules = data.get("rules", [])
    default_action = data.get("default", "error")
    return rules, default_action


def match_payment_method(rules: list[dict], source: str, payment_method: str) -> dict | None:
    """按 (source, payment_method) 匹配规则，返回 {account, currency} 或 None
    
    优先级：长规则优先（精确匹配 > 前缀匹配 > 通配 *）
    """
    candidates = []
    for rule in rules:
        if rule.get("source") != source:
            continue
        pattern = rule["match"]
        if fnmatch.fnmatch(payment_method, pattern):
            candidates.append((len(pattern), rule))

    if not candidates:
        return None

    candidates.sort(key=lambda x: -x[0])
    return {
        "account": candidates[0][1]["account"],
        "currency": candidates[0][1]["currency"],
    }
