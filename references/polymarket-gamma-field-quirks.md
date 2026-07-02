# Polymarket Gamma field quirks

## Symptom
`ft stock list` / `_fetch_prices()` may show the wrong Polymarket market price, even though the slug is correct.

## Cause
`https://gamma-api.polymarket.com/markets?slug=...` can return some list-like fields as **JSON strings** instead of native arrays, for example:

- `outcomes = "[\"Yes\",\"No\"]"`
- `outcomePrices = "[\"0.08\",\"0.92\"]"`

If code treats these as normal iterables, it iterates characters and silently builds nonsense mappings.

## Fix
Parse stringified list fields with `json.loads()` when the field is a string and the trimmed text starts with `[`.

Recommended helper pattern:

```python
def _coerce_json_list(value):
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        text = value.strip()
        if text:
            parsed = json.loads(text)
            if isinstance(parsed, list):
                return parsed
            return [parsed]
    return []
```

## Verification
Use a regression test with a fake gamma response that returns stringified lists and confirm:

- `pm:<slug>:yes` resolves to the Yes price
- `pm:<slug>:no` resolves to the No price

## Notes
- Keep the `User-Agent` header when calling Gamma API.
- This quirk affects price lookup and therefore security-account valuation for Polymarket holdings.
