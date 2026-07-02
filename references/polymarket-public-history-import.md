# Polymarket public history import into `ft`

Use this when the user wants to connect a Polymarket account and update `ft` from order/trade history.

## Address resolution

A user may give the primary/profile address rather than the proxy wallet used by Polymarket Data API.

1. Fetch `https://polymarket.com/profile/<address>` with a browser-like `User-Agent`.
2. Parse `proxyAddress` from the embedded Next.js data. The HTML often escapes JSON, so match both raw and escaped forms, e.g. `\"proxyAddress\":\"0x...\"`.
3. Use the proxy wallet for Data API calls.

## Public endpoints

Use public Data API first; no private key or API secret is needed for filled public activity.

```text
GET https://data-api.polymarket.com/activity?user=<proxyWallet>&limit=500&offset=0
GET https://data-api.polymarket.com/trades?user=<proxyWallet>&limit=500&offset=0
```

Practical notes:
- `/activity` can contain more complete trade split rows than `/trades`; prefer `/activity` for replaying the ledger.
- Filter `type == "TRADE"`.
- Paginate with `offset` until the returned page is empty or shorter than `limit`.
- Always include a browser-like `User-Agent`.
- `usdcSize` is the authoritative cash amount when present; do not recompute from `size * price` if `usdcSize` is available.

## Conversion to `ft` security CSV

Map each TRADE row to stock CSV fields:

| ft field | Polymarket field / rule |
|---|---|
| `date` | `datetime.fromtimestamp(timestamp)` local time |
| `action` | `BUY` or `SELL` from `side` |
| `ticker` | `pm:<slug>:<outcome.lower()>` |
| `shares` | `size` |
| `price` | `price` |
| `amount` | `-usdcSize` for BUY, `+usdcSize` for SELL |
| `commission` | `0` |
| `currency` | `USD` |
| `account_name` | `Polymarket` |
| `note` | market title + outcome + transaction hash |

Only use this simple ticker convention for `Yes`/`No` outcomes. If outcomes are not Yes/No, stop and design a compatible ticker scheme instead of silently importing.

## Existing-data pitfall

Do **not** directly append public history over previously hand-entered Polymarket rows. Existing records may contain:

- approximate timestamps/prices/shares;
- `CHECKIN` rows created from prior snapshots;
- older slugs that differ from current public API slugs;
- partial rows imported from `/trades` when `/activity` has additional split fills.

For a full refresh:

1. Back up `~/.ft/records/security/`.
2. Remove existing rows with `account_name == "Polymarket"` from security stock CSVs, including old BUY/SELL/CHECKIN rows.
3. Preserve cash-style transfer rows involving the Polymarket account, such as deposits from another security account; these are not stock trades.
4. Append the newly converted `/activity` TRADE CSV.
5. Run `ft verify --fix` and then `ft stock list`.
6. Compare replayed net positions against the public Activity net positions before reporting success.

## Validation summaries to show before destructive replacement

Before modifying `~/.ft`, show the user:

- input address and resolved proxy wallet;
- counts from `/trades` and `/activity`;
- number of convertible Yes/No trades;
- number of unsupported outcomes;
- net cash flow from replayed trades;
- net position per `pm:` ticker;
- explicit warning that direct append would double-count if old rows exist.

Wait for confirmation before deleting or replacing existing Polymarket rows.
