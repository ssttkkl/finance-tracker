#!/usr/bin/env bash
set -euo pipefail

command -v python3 >/dev/null 2>&1 || {
  printf 'build-wasm-web: 需要预装命令：python3\n' >&2
  exit 1
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/build-wasm-web.py" "$@"
