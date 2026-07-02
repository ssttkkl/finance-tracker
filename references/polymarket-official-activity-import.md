# Polymarket official activity import → ft

Use this when replacing hand-entered / approximate Polymarket rows in `ft` with official Polymarket public trade activity.

## Address resolution

A Polymarket profile/login address may not be the address accepted by the public data API. Resolve the profile page first and extract the proxy wallet:

1. User provides a public `0x...` profile/wallet address. Never ask for private key, seed phrase, API secret, or browser session cookies.
2. Fetch `https://polymarket.com/profile/<address>` with a browser-like `User-Agent`.
3. Extract `proxyAddress` from the page payload, e.g. escaped JSON contains `\"proxyAddress\":\"0x...\"`.
4. Use the proxy address for Data API calls.

## Public Data API choice

Prefer Activity over Trades for full filled-trade import:

```text
GET https://data-api.polymarket.com/activity?user=<proxyWallet>&limit=500&offset=0
```

Why: `/trades` can omit split sell fills that `/activity` includes. Treat `type == "TRADE"` activity rows as the authoritative filled-trade stream for this import workflow.

Useful fields:

- `timestamp` → local `YYYY-MM-DD HH:MM:SS`
- `side` → `BUY` / `SELL`
- `slug` + `outcome` → `pm:<slug>:yes|no`
- `size` → shares
- `price` → price
- `usdcSize` → amount basis; BUY stored negative, SELL stored positive
- `transactionHash` → include in `note` for idempotence/audit

## Conversion to ft stock CSV

Target fields:

```csv
date,action,ticker,shares,price,amount,commission,currency,account_name,note
```

Rules:

- Only import `outcome` values that map cleanly to `yes` / `no` unless the tracker has been extended for other outcome labels.
- `ticker = pm:<slug>:<outcome.lower()>`.
- `amount = -usdcSize` for BUY, `+usdcSize` for SELL.
- `commission = 0`, `currency = USD`, `account_name = Polymarket`.
- Include `tx:<transactionHash>` in `note`.

## Full replacement workflow

When the user asks to sync/replace Polymarket history:

1. Back up `~/.ft/records/security/`, `accounts.yaml`, and `snapshot.yaml` **outside the git repo** (for example `~/.ft_backups/<timestamp>`), or move the backup out before `git add -A`. Do not accidentally stage large backup directories under `~/.ft`.
2. Remove old Polymarket security rows where `account_name == Polymarket` and `action in {BUY, SELL, CHECKIN, DIVIDEND}`.
3. Preserve cash-style transfer audit rows, such as `东方证券 -> Polymarket`; they are not stock-trade rows and may not have `action` / `ticker` fields.
4. Import the official Activity-derived CSV with `~/bin/ft stock append <csv>`.
5. If the preserved cross-account transfer was cash-style and security replay does not count it, add an explicit security cash row:
   ```bash
   ~/bin/ft stock deposit --amount <usd_amount> --account Polymarket --currency USD --date '<transfer_date>' --note '对应换汇转入 Polymarket（补 security cash replay）'
   ```
   This prevents Polymarket security cash from being understated while still preserving the original transfer audit row.
6. Run `~/bin/ft verify`.
7. Run `HTTP_PROXY=${HTTP_PROXY:-http://127.0.0.1:7890} HTTPS_PROXY=${HTTPS_PROXY:-http://127.0.0.1:7890} ~/bin/ft stock list` and report the Polymarket section.
8. Do not auto-commit unless the user asks; `ft` will stage changes automatically.

## Dedup / replacement cautions

- Do not append official history on top of old hand-entered rows: older rows may have approximate timestamps/prices and legacy slugs, so exact-row dedupe will miss them.
- If doing incremental import instead of full replacement, dedupe using transaction hash first, then `date + action + ticker + shares + price + amount`.
- Existing historical `CHECKIN` rows can double-count positions after official Activity is imported; remove them during full replacement.
- Tiny residual shares (e.g. `0.0009`) can appear after official split fills because Activity exposes rounded filled sizes while the official Positions API may already report the market as closed/zero-size. After importing Activity, always compare `snapshot.yaml` Polymarket positions against `GET https://data-api.polymarket.com/positions?user=<proxyWallet>&sizeThreshold=0&limit=200`. If a ticker is present in ft with `abs(shares) < 0.01` but missing/zero in Positions API, add an explicit zero-share `CHECKIN` row immediately after the final trade (same ticker, `shares=0, price=0, amount=0`) with a note referencing Positions API cleanup. Do not delete official trade rows.
