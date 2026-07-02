# yfinance market grouping quirk

## Symptom
When `ft stock list` fetched prices for a mixed basket containing US (`.US`), China A-shares (`.SZ` / `.SS`), and HK (`.HK`) tickers in the same `yfinance.download(...)` batch, the resulting Close series could contain `NaN` for the US names, causing portfolio market values to print as `nan`.

## Repro
- Mixed basket example: `nvda.us`, `mu.us`, `avgo.us`, `159330.sz`, `159740.sz`, `00700.hk`
- Batch download returned valid rows for some markets, but US tickers were `NaN` in the extracted Close data.

## Fix pattern
1. Split downloads by market:
   - US tickers: `.US`
   - A-shares: `.SZ`
   - Shanghai: `.SS`
   - HK: `.HK`
2. Keep HK tickers in their own single-ticker downloads if needed.
3. If a batched download still returns `NaN` or missing data for a name, retry that ticker individually as a fallback.

## Verification
Use a small probe that calls `_fetch_prices([...])` with a mixed basket and verify every ticker returns a numeric price, especially after adding new markets or changing ticker normalization.
