"""Payment mapping: longer match wins; default skip/error."""
from pathlib import Path

import pytest
import yaml


def _write_mapping(path: Path, rules: list[dict], default: str = "error") -> Path:
    path.write_text(
        yaml.safe_dump({"rules": rules, "default": default}, allow_unicode=True),
        encoding="utf-8",
    )
    return path


def test_longer_match_wins(tmp_path):
    from ft.mapping import load_rules, match_payment_method

    mapping = _write_mapping(
        tmp_path / "mapping.yaml",
        [
            {
                "source": "alipay",
                "match": "工商银行信用卡*",
                "account": "Short",
                "currency": "CNY",
            },
            {
                "source": "alipay",
                "match": "工商银行信用卡(1200)*",
                "account": "Long",
                "currency": "CNY",
            },
        ],
    )
    rules, default = load_rules(mapping)
    assert default == "error"
    match = match_payment_method(rules, "alipay", "工商银行信用卡(1200)分期")
    assert match is not None
    assert match["account"] == "Long"


def test_empty_payment_method_can_match_empty_pattern(tmp_path):
    from ft.mapping import load_rules, match_payment_method

    mapping = _write_mapping(
        tmp_path / "mapping.yaml",
        [
            {
                "source": "alipay",
                "match": "",
                "account": "支付宝余额",
                "currency": "CNY",
            }
        ],
    )
    rules, _ = load_rules(mapping)
    match = match_payment_method(rules, "alipay", "")
    assert match is not None
    assert match["account"] == "支付宝余额"


def test_no_match_returns_none(tmp_path):
    from ft.mapping import load_rules, match_payment_method

    mapping = _write_mapping(
        tmp_path / "mapping.yaml",
        [
            {
                "source": "alipay",
                "match": "账户余额",
                "account": "支付宝余额",
                "currency": "CNY",
            }
        ],
        default="skip",
    )
    rules, default = load_rules(mapping)
    assert default == "skip"
    assert match_payment_method(rules, "alipay", "未知支付方式") is None


@pytest.mark.parametrize(
    ("source", "canonical", "legacy"),
    [
        ("alipay", "支付宝余额", "账户余额"),
        ("alipay", "支付宝余额", "余额"),
        ("wechat", "微信零钱", "零钱"),
    ],
)
def test_canonical_wallet_names_match_legacy_mapping_rules(tmp_path, source, canonical, legacy):
    from ft.mapping import load_rules, match_payment_method

    mapping = _write_mapping(
        tmp_path / f"{source}.yaml",
        [{"source": source, "match": legacy, "account": canonical, "currency": "CNY"}],
    )
    rules, _ = load_rules(mapping)
    match = match_payment_method(rules, source, canonical)
    assert match == {"account": canonical, "currency": "CNY"}


@pytest.mark.parametrize(
    ("source", "canonical", "legacy"),
    [
        ("alipay", "支付宝余额", "账户余额"),
        ("wechat", "微信零钱", "零钱"),
    ],
)
def test_legacy_exact_rule_beats_canonical_catch_all(tmp_path, source, canonical, legacy):
    from ft.mapping import load_rules, match_payment_method

    mapping = _write_mapping(
        tmp_path / f"{source}-specific.yaml",
        [
            {"source": source, "match": "*", "account": "通用账户", "currency": "CNY"},
            {"source": source, "match": legacy, "account": "历史钱包", "currency": "CNY"},
        ],
    )
    rules, _ = load_rules(mapping)
    assert match_payment_method(rules, source, canonical) == {
        "account": "历史钱包", "currency": "CNY",
    }


def test_load_rules_creates_default_template(tmp_path, monkeypatch):
    from ft import mapping as mapping_mod

    target = tmp_path / ".ft" / "mapping.yaml"
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", target)
    rules, default = mapping_mod.load_rules()
    assert target.exists()
    assert isinstance(rules, list)
    assert default in {"skip", "error", "fail"}
    assert not any(rule.get("source") in {"icbc_credit", "icbc_debit"} for rule in rules)
