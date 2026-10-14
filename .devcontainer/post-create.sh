#!/usr/bin/env bash
set -euo pipefail

uv sync
(cd web && npm ci --no-audit --no-fund)

for database in finance_tracker finance_tracker_test; do
  if ! psql -U postgres -d postgres -Atqc \
    "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -qx '1'; then
    createdb -U postgres "${database}"
  fi
done

(cd web && npx playwright install chromium)
