# yfinance HK price fetch notes

## Symptom
`ft stock list` could normalize `00700.hk -> 0700.HK`, but HK prices still occasionally came back empty or partial.

## Root cause
`yfinance.download()` returns different shapes depending on the request:
- single HK ticker: often a plain `DataFrame` with `Close` as a Series-like column
- multi-ticker requests: sometimes a `DataFrame` with `MultiIndex` columns
- mixing HK with non-HK tickers can cause HK rows to disappear in the grouped result

## Fix pattern
1. Normalize `00700.hk -> 0700.HK`.
2. Split HK tickers from non-HK tickers before download.
3. For each download result, extract the last close defensively:
   - try `data["Close"]`
   - fall back to `data.xs("Close", axis=1, level=0)`
   - handle both Series and DataFrame shapes
4. If a grouped HK fetch is flaky, retry one ticker at a time.

## Verification
Use a mocked `yfinance.download()` in tests to cover:
- single HK ticker path
- multi-ticker `MultiIndex` path
- fallback when `HTTP_PROXY` is present
