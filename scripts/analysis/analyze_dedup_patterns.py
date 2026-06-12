#!/usr/bin/env python3
"""Analyze duplicate transaction patterns in the merged bill CSV."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import pandas as pd


APP_SOURCES = {"支付宝", "微信"}
CREDIT_ACCOUNTS = {"工行信用卡(1200)", "工行信用卡(9166)"}
CARD_ONLY_SOURCES = {
    "美团支付",
    "微信支付",
    "银行卡",
    "网银在线",
    "京东支付",
    "Apple Pay",
    "携程",
    "银联云闪付",
    "抖音支付",
}


def signed_amount_cents(series: pd.Series) -> pd.Series:
    return (series.astype(float).round(2) * 100).round().astype(int)


def classify_origin(row: pd.Series) -> str:
    if row["account_name"] in CREDIT_ACCOUNTS and row["source"] in CARD_ONLY_SOURCES:
        return "icbc_credit_likely"
    if row["source"] == "微信":
        return "wechat_app_likely"
    if row["source"] == "支付宝" and row["account_name"] not in CREDIT_ACCOUNTS:
        return "alipay_app_likely"
    if row["source"] == "支付宝" and row["account_name"] in CREDIT_ACCOUNTS:
        return "ambiguous_alipay_or_icbc_credit"
    if row["account_name"] == "工行借记卡":
        return "icbc_debit_likely"
    return "other"


def group_summary(df: pd.DataFrame, cols: list[str], min_size: int = 2) -> pd.DataFrame:
    groups = (
        df.groupby(cols, dropna=False)
        .agg(
            count=("row_no", "size"),
            rows=("row_no", lambda x: ",".join(map(str, x))),
            accounts=("account_name", lambda x: " | ".join(sorted(set(map(str, x))))),
            sources=("source", lambda x: " | ".join(sorted(set(map(str, x))))),
            counterparties=("counterparty", lambda x: " | ".join(sorted(set(map(str, x))))),
            platforms=("platform", lambda x: " | ".join(sorted(set(map(str, x))))),
            origins=("origin_guess", lambda x: " | ".join(sorted(set(map(str, x))))),
        )
        .reset_index()
    )
    return groups[groups["count"] >= min_size].sort_values(["count", *cols], ascending=[False] + [True] * len(cols))


def build_near_matches(df: pd.DataFrame, max_minutes: int) -> pd.DataFrame:
    # Candidate pairs with same signed amount and currency, different rows,
    # close timestamps, and at least one likely/ambiguous app-credit overlap.
    work = df[df["category"].eq("expense")].copy()
    work = work[work["amount_cents"] < 0]
    merged = work.merge(work, on=["amount_cents", "currency"], suffixes=("_a", "_b"))
    merged = merged[merged["row_no_a"] < merged["row_no_b"]].copy()
    merged["delta_seconds"] = (merged["dt_a"] - merged["dt_b"]).abs().dt.total_seconds().astype(int)
    merged = merged[merged["delta_seconds"] <= max_minutes * 60].copy()
    merged["same_counterparty"] = merged["counterparty_a"] == merged["counterparty_b"]
    merged["same_platform"] = (
        merged["platform_a"].fillna("").ne("")
        & (merged["platform_a"] == merged["platform_b"])
    )
    merged["same_account"] = merged["account_name_a"] == merged["account_name_b"]
    merged["source_pair"] = merged.apply(lambda r: " <-> ".join(sorted([r["source_a"], r["source_b"]])), axis=1)
    merged["origin_pair"] = merged["origin_guess_a"] + " <-> " + merged["origin_guess_b"]
    merged["card_app_like"] = merged.apply(
        lambda r: (
            (
                r["origin_guess_a"]
                in {"icbc_credit_likely", "ambiguous_alipay_or_icbc_credit"}
                and r["origin_guess_b"]
                in {"wechat_app_likely", "alipay_app_likely", "ambiguous_alipay_or_icbc_credit"}
            )
            or (
                r["origin_guess_b"]
                in {"icbc_credit_likely", "ambiguous_alipay_or_icbc_credit"}
                and r["origin_guess_a"]
                in {"wechat_app_likely", "alipay_app_likely", "ambiguous_alipay_or_icbc_credit"}
            )
        ),
        axis=1,
    )
    return merged.sort_values(["delta_seconds", "amount_cents", "row_no_a", "row_no_b"])


def print_section(title: str) -> None:
    print("\n" + "=" * 100)
    print(title)
    print("=" * 100)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        default="/Users/huangwenlong/Downloads/all_bills_merged.csv",
        help="Merged unified CSV path",
    )
    parser.add_argument("--outdir", default="outputs/dedup_analysis")
    parser.add_argument("--max-minutes", type=int, default=3)
    args = parser.parse_args()

    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(args.input)
    df.insert(0, "row_no", range(2, len(df) + 2))
    df["dt"] = pd.to_datetime(df["date"])
    df["amount_cents"] = signed_amount_cents(df["amount"])
    df["origin_guess"] = df.apply(classify_origin, axis=1)

    print_section("DATASET")
    print(f"rows={len(df)}")
    print(f"date_range={df['dt'].min()} -> {df['dt'].max()}")
    for col in ["account_name", "source", "origin_guess", "category", "currency"]:
        print(f"\n{col}")
        print(df[col].value_counts(dropna=False).to_string())

    print_section("EXACT DUPLICATE GROUPS")
    exact_keys = [
        ["date", "amount_cents", "currency"],
        ["date", "amount_cents", "currency", "counterparty"],
        ["date", "amount_cents", "currency", "counterparty", "account_name"],
        ["date", "amount_cents", "currency", "source", "counterparty"],
    ]
    for cols in exact_keys:
        groups = group_summary(df, cols)
        print(f"\nkey={cols}: groups={len(groups)}, excess_rows={(groups['count'] - 1).sum() if len(groups) else 0}")
        if len(groups):
            print(groups.head(15).to_string(index=False))

    print_section(f"NEAR MATCHES WITH SAME AMOUNT/CURRENCY WITHIN {args.max_minutes} MINUTES")
    near = build_near_matches(df, args.max_minutes)
    near.to_csv(outdir / "near_matches_all.csv", index=False)
    likely = near[near["card_app_like"]].copy()
    likely.to_csv(outdir / "near_matches_card_app_like.csv", index=False)
    strict = likely[
        (likely["same_counterparty"] | likely["same_platform"] | (likely["delta_seconds"] <= 10))
        & (likely["row_no_a"] != likely["row_no_b"])
    ].copy()
    strict.to_csv(outdir / "near_matches_card_app_strict.csv", index=False)
    print(f"all_near_pairs={len(near)}")
    print(f"card_app_like_pairs={len(likely)}")
    print(f"strict_pairs={len(strict)}")
    if len(near):
        print("\nsource_pair counts")
        print(near["source_pair"].value_counts().head(30).to_string())
        print("\norigin_pair counts")
        print(near["origin_pair"].value_counts().head(30).to_string())
    if len(strict):
        cols = [
            "row_no_a",
            "date_a",
            "amount_a",
            "account_name_a",
            "source_a",
            "counterparty_a",
            "platform_a",
            "origin_guess_a",
            "row_no_b",
            "date_b",
            "amount_b",
            "account_name_b",
            "source_b",
            "counterparty_b",
            "platform_b",
            "origin_guess_b",
            "delta_seconds",
            "same_counterparty",
            "same_platform",
        ]
        print("\nstrict sample")
        print(strict[cols].head(80).to_string(index=False))

    print_section("AMOUNT/TIME COLLISION RISK")
    for minutes in [0, 1, 3, 5, 30, 1440]:
        n = build_near_matches(df, minutes)
        print(
            f"window={minutes:>4} min: pairs={len(n):>4}, "
            f"same_counterparty={int(n['same_counterparty'].sum()) if len(n) else 0:>4}, "
            f"same_platform={int(n['same_platform'].sum()) if len(n) else 0:>4}, "
            f"card_app_like={int(n['card_app_like'].sum()) if len(n) else 0:>4}"
        )

    print_section("APP/CREDIT ACCOUNT CLUES")
    credit_paid_apps = df[
        df["account_name"].isin(CREDIT_ACCOUNTS)
        & df["source"].isin(APP_SOURCES | {"微信支付"})
        & df["category"].eq("expense")
    ]
    print(f"credit-account app/payment-source expenses={len(credit_paid_apps)}")
    print(credit_paid_apps[["row_no", "date", "amount", "account_name", "source", "counterparty", "platform", "origin_guess"]].head(80).to_string(index=False))

    print_section("COUNTERPARTY NORMALIZATION CLUES")
    cp_counts = Counter()
    for cp in df["counterparty"].dropna().astype(str):
        cp_counts.update([cp[:2], cp[:3], cp[:4]])
    for source_pair, sample in near.groupby("source_pair").head(5).groupby("source_pair"):
        print(f"\n{source_pair}")
        for _, r in sample.iterrows():
            print(
                f"  rows {r.row_no_a}/{r.row_no_b} amount={r.amount_a} delta={r.delta_seconds}s "
                f"cp=({r.counterparty_a}) vs ({r.counterparty_b}) platform=({r.platform_a})/({r.platform_b})"
            )


if __name__ == "__main__":
    main()
