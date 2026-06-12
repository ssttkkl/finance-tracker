"""YAML account management — load, save, find, add accounts."""
import logging
from pathlib import Path
from typing import Optional

import yaml

from ft import models
from ft.models import ACCOUNT_TYPES, CURRENCIES

logger = logging.getLogger(__name__)

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


def load_accounts(path: Optional[Path] = None) -> list[dict]:
    """加载账户列表。

    如果文件不存在则创建默认账户文件再读取。
    """
    path = path or models.ACCOUNTS_PATH

    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_ACCOUNTS_YAML, encoding="utf-8")
        logger.info("已创建默认账户文件: %s", path)

    with path.open("r", encoding="utf-8") as f:
        data = yaml.safe_load(f)

    if data is None:
        return []

    return data.get("accounts", [])


def save_accounts(accounts: list[dict], path: Optional[Path] = None) -> None:
    """保存账户列表到 YAML 文件。"""
    path = path or models.ACCOUNTS_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        yaml.dump(
            {"accounts": accounts},
            f,
            allow_unicode=True,
            default_flow_style=False,
        )


def find_account(name: str, path: Optional[Path] = None) -> Optional[dict]:
    """按名称查找账户。

    优先返回 active=True 的账户；若未找到则返回 None。
    """
    accounts = load_accounts(path)

    # 先找 active 的
    for acct in accounts:
        if acct.get("name") == name and acct.get("active", True):
            return acct

    # 再找 inactive 的
    for acct in accounts:
        if acct.get("name") == name:
            return acct

    return None


def add_account(
    name: str,
    type_: str,
    currency: str,
    path: Optional[Path] = None,
) -> None:
    """添加新账户。

    校验 type_ 和 currency 合法性。
    若名称+币种已存在则给出警告并不重复添加。
    """
    if type_ not in ACCOUNT_TYPES:
        raise ValueError(f"无效账户类型 '{type_}'，可用类型: {ACCOUNT_TYPES}")

    if currency not in CURRENCIES:
        raise ValueError(f"无效币种 '{currency}'，可用币种: {CURRENCIES}")

    accounts = load_accounts(path)

    # 检查名称+币种是否已存在
    for acct in accounts:
        if acct.get("name") == name and acct.get("currency") == currency:
            logger.warning("账户已存在: %s (%s)，跳过添加", name, currency)
            return

    new_account = {
        "name": name,
        "type": type_,
        "currency": currency,
        "active": True,
    }
    accounts.append(new_account)
    save_accounts(accounts, path)
    logger.info("已添加账户: %s (%s / %s)", name, type_, currency)
