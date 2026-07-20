# Contract: Open Currency

## Validation

- Accept currency codes matching `^[A-Za-z]{3}$`, normalized to uppercase.
- Reject empty, non-alpha, wrong length with clear error.
- Do **not** reject JPY or other codes solely for not being CNY/USD/HKD.

## Surfaces

- `ft acct add NAME --type T --currency CODE`
- `ft add|transfer|checkin|import|convert|stock *` currency flags
- Application account service
- Domain account DTO construction

## Display

- Known symbols in `CURRENCY_SYMBOLS` may be used.
- Unknown codes display as the code itself; must not raise.
