"""Credential loading for exchange and connector API access.

Loads provider credentials from ``~/.ft/credentials.yaml`` (or ``FT_CREDENTIALS_DIR``).
Auto-protects the file (chmod 600, gitignore). Never leaks secret values in errors.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any


CREDENTIALS_FILENAME = "credentials.yaml"
EXCHANGE_REQUIRED_FIELDS = ("api_key", "api_secret")
_WALLET_RE = re.compile(r"^0x[a-fA-F0-9]{40}$")


def _credentials_dir() -> Path:
    """Return the directory holding credentials.yaml."""
    custom = os.environ.get("FT_CREDENTIALS_DIR")
    if custom:
        return Path(custom)
    return Path.home() / ".ft"


def _credentials_path() -> Path:
    return _credentials_dir() / CREDENTIALS_FILENAME


def _ensure_gitignore(directory: Path) -> None:
    """Ensure credentials.yaml is listed in the directory's .gitignore."""
    directory.mkdir(parents=True, exist_ok=True)
    gitignore = directory / ".gitignore"
    lines: list[str] = []
    if gitignore.exists():
        lines = gitignore.read_text(encoding="utf-8").splitlines()
    if CREDENTIALS_FILENAME not in {ln.strip() for ln in lines}:
        lines.append(CREDENTIALS_FILENAME)
        gitignore.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _protect_file(path: Path) -> None:
    """Set file permissions to 0600 (owner-only read/write)."""
    if path.exists():
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass  # Windows or permission issues — best effort


def _read_credentials_file() -> dict[str, Any]:
    """Read and parse the credentials YAML file.

    Raises ValueError with actionable guidance (but no secret values).
    """
    path = _credentials_path()
    if not path.exists():
        raise ValueError(
            f"Credentials file not found: {path}\n"
            f"Create it with your provider credentials, e.g.:\n"
            f"  binance:\n"
            f"    api_key: \"your-api-key\"\n"
            f"    api_secret: \"your-api-secret\"\n"
            f"  polymarket:\n"
            f"    proxy_wallet: \"0x...\""
        )
    _protect_file(path)
    _ensure_gitignore(path.parent)

    try:
        import yaml
    except ImportError as exc:
        raise ValueError(
            "PyYAML is required for credentials loading: pip install pyyaml"
        ) from exc

    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"Cannot read credentials file {path}: {exc}") from exc

    if not text.strip():
        raise ValueError(
            f"Credentials file is empty: {path}\n"
            f"Add provider sections with required fields."
        )

    try:
        data = yaml.safe_load(text)
    except Exception as exc:
        raise ValueError(
            f"Invalid YAML in credentials file {path}: {type(exc).__name__}"
        ) from exc

    if not isinstance(data, dict):
        raise ValueError(
            f"Credentials file {path} must contain a YAML mapping (provider → config)"
        )
    return data


def load_exchange_credentials(provider: str) -> dict[str, str]:
    """Load exchange API credentials for a ccxt provider.

    Returns dict with at least 'api_key' and 'api_secret'.
    Never includes secret values in error messages.
    """
    data = _read_credentials_file()
    section = data.get(provider)
    if section is None or not section:
        raise ValueError(
            f"Missing '{provider}' section in credentials file.\n"
            f"Add the following to {_credentials_path()}:\n"
            f"  {provider}:\n"
            f"    api_key: \"your-api-key\"\n"
            f"    api_secret: \"your-api-secret\""
        )
    if not isinstance(section, dict):
        raise ValueError(f"Credentials '{provider}' must be a mapping")
    missing = [f for f in EXCHANGE_REQUIRED_FIELDS if not section.get(f)]
    if missing:
        raise ValueError(
            f"Credentials '{provider}' is missing required field(s): {', '.join(missing)}\n"
            f"Required fields: {', '.join(EXCHANGE_REQUIRED_FIELDS)}"
        )
    return {
        "api_key": str(section["api_key"]),
        "api_secret": str(section["api_secret"]),
        **({k: str(v) for k, v in section.items() if k not in EXCHANGE_REQUIRED_FIELDS and k == "password"}),
    }


def load_polymarket_credentials() -> dict[str, str]:
    """Load Polymarket wallet credentials.

    Returns dict with 'wallet' and/or 'proxy_wallet'.
    """
    data = _read_credentials_file()
    section = data.get("polymarket")
    if not isinstance(section, dict) or not section:
        raise ValueError(
            f"Missing 'polymarket' section in credentials file.\n"
            f"Add the following to {_credentials_path()}:\n"
            f"  polymarket:\n"
            f"    proxy_wallet: \"0x...\"  # or wallet: \"0x...\""
        )
    wallet = section.get("wallet")
    proxy_wallet = section.get("proxy_wallet")
    if not wallet and not proxy_wallet:
        raise ValueError(
            f"Credentials 'polymarket' requires 'wallet' or 'proxy_wallet'.\n"
            f"Add to {_credentials_path()}:\n"
            f"  polymarket:\n"
            f"    proxy_wallet: \"0x...\""
        )
    result: dict[str, str] = {}
    if wallet:
        w = str(wallet).strip()
        if not _WALLET_RE.match(w):
            raise ValueError(
                "Polymarket 'wallet' must be a valid Ethereum address (0x + 40 hex chars)"
            )
        result["wallet"] = w.lower()
    if proxy_wallet:
        pw = str(proxy_wallet).strip()
        if not _WALLET_RE.match(pw):
            raise ValueError(
                "Polymarket 'proxy_wallet' must be a valid Ethereum address (0x + 40 hex chars)"
            )
        result["proxy_wallet"] = pw.lower()
    return result
