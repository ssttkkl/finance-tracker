#!/usr/bin/env bash
set -euo pipefail

uv sync
npm ci --no-audit --no-fund

for database in finance_tracker finance_tracker_test; do
  if ! psql -U postgres -d postgres -Atqc \
    "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -qx '1'; then
    createdb -U postgres "${database}"
  fi
done

npm exec --workspace finance-tracker-web -- playwright install chromium
