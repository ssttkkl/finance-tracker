# Contract: Raw Payload

- Every new formal fact has raw_record with payload keys per source (spec appendix).
- Payload is JSON-serializable; Decimal → string.
- PG/SQLite round-trip preserves required keys.
