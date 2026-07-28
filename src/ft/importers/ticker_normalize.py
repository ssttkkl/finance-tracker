"""Shared equity ticker normalization for investment importers.

Convention (all sources):
- US equity: ``CODE.us`` (e.g. ``mrvl.us``, ``aapl.us``)
- HK equity: ``CODE.hk`` (e.g. ``00700.hk``)
- CN A-share: ``CODE.sh`` / ``CODE.sz`` (DFZQ / A-share connect heuristics)
- Cash / FX tickers: bare ISO lowercase (``usd``, ``hkd``) — never pass through here
- FX pairs (``USD.HKD``): not equity — do not call this helper

Idempotent: if the code already ends with a known market suffix, keep it.
"""
from __future__ import annotations

from ft.schema import CRYPTO_IDS

_KNOWN_SUFFIXES = frozenset({
    "us", "hk", "sh", "sz", "cn", "otc",
})

# Fiat cash tickers — never append .us
_FIAT = frozenset({
    "usd", "hkd", "cny", "eur", "gbp", "jpy", "aud", "cad", "chf", "nzd", "sgd",
})

_CRYPTO_ALIASES = {
    "xbt": "btc",  # Kraken's BTC symbol
    "xbtc": "btc",
    "xdg": "doge",
}


def normalize_crypto_ticker(ticker: str) -> str:
    """Return a canonical lowercase crypto ticker without rejecting unknowns."""
    token = (ticker or "").strip().lower()
    canonical = _CRYPTO_ALIASES.get(token, token)
    return canonical if canonical in CRYPTO_IDS else token


def normalize_equity_ticker(
    code: str,
    *,
    market: str = "",
    ccy: str = "",
    default_market: str = "us",
) -> str:
    """Return lowercased equity ticker with exchange suffix.

    Parameters
    ----------
    code:
        Broker symbol (``MRVL``, ``00700``, ``AAPL.US``).
    market:
        Optional venue label: ``美股`` / ``港股`` / ``A股通`` / ``US`` / ``HK`` / …
    ccy:
        Optional trade currency (``USD`` / ``HKD`` / ``CNY``).
    default_market:
        Used when market/ccy do not imply a venue (Schwab/IBKR equity → ``us``).
    """
    token = (code or "").strip()
    if not token:
        raise ValueError("empty equity ticker")
    # FX pair BASE.QUOTE — caller must not use this helper
    if "." in token:
        head, _, tail = token.partition(".")
        if tail.lower() in _KNOWN_SUFFIXES:
            return f"{head.lower()}.{tail.lower()}"
        # e.g. USD.HKD — leave for FX mappers
        return token.lower()

    lower = token.lower()
    if lower in _FIAT:
        return lower

    market_n = (market or "").strip().lower()
    ccy_u = (ccy or "").strip().upper()

    if market_n in {"港股", "hk", "hkex", "hong kong"} or ccy_u == "HKD":
        return f"{lower}.hk"
    if market_n in {"a股通", "a-share", "ashare", "cn", "china"} or ccy_u == "CNY":
        if lower[:1] in {"5", "6"}:
            return f"{lower}.sh"
        if lower[:1] in {"0", "1", "2", "3"}:
            return f"{lower}.sz"
        return f"{lower}.cn"
    if market_n in {"美股", "us", "usa", "nyse", "nasdaq"} or ccy_u == "USD":
        return f"{lower}.us"
    if default_market == "hk":
        return f"{lower}.hk"
    if default_market == "us":
        return f"{lower}.us"
    return f"{lower}.{default_market}"
