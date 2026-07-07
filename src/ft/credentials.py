"""Exchange API credentials: read from ~/.ft/credentials.yaml, keep it private."""
from __future__ import annotations

import os
from pathlib import Path

import yaml

from . import models

CREDENTIALS_FILENAME = "credentials.yaml"
REQUIRED_FIELDS = ("api_key", "api_secret")


def _credentials_path() -> Path:
    return Path(models.FT_DIR) / CREDENTIALS_FILENAME


def _read_credentials_file(example: str) -> tuple[Path, dict]:
    path = _credentials_path()
    if not path.exists():
        raise ValueError(f"未找到凭证文件 {path}，请创建并写入：\n{example}")
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"凭证文件 {path} 格式错误：顶层应为 provider 映射")
    return path, data


def load_credentials(provider: str) -> dict:
    """Load one exchange provider's API credential section. Never echoes secret values."""
    path, data = _read_credentials_file(
        f"{provider}:\n  api_key: \"...\"\n  api_secret: \"...\""
    )
    section = data.get(provider)
    if not isinstance(section, dict) or not section:
        raise ValueError(
            f"凭证文件 {path} 缺少 '{provider}' 段，请补充 api_key/api_secret"
        )
    for field in REQUIRED_FIELDS:
        if not section.get(field):
            raise ValueError(f"凭证 '{provider}' 缺少必填字段 '{field}'（见 {path}）")
    return section


def load_polymarket_credentials() -> dict:
    """Load Polymarket wallet config from credentials.yaml."""
    path, data = _read_credentials_file(
        "必须指定 wallet 或 proxy_wallet，或配置：\n"
        "polymarket:\n  wallet: \"0x...\"        # 或 proxy_wallet: \"0x...\""
    )
    section = data.get("polymarket")
    if not isinstance(section, dict) or not section:
        raise ValueError(
            f"凭证文件 {path} 缺少 'polymarket' 段，请补充 wallet 或 proxy_wallet"
        )
    if not section.get("wallet") and not section.get("proxy_wallet"):
        raise ValueError(f"凭证 'polymarket' 缺少 wallet 或 proxy_wallet（见 {path}）")
    return section


def ensure_credentials_gitignored() -> None:
    """Ensure credentials.yaml is gitignored under FT_DIR and chmod 600 if present."""
    ft_dir = Path(models.FT_DIR)
    ft_dir.mkdir(parents=True, exist_ok=True)
    gitignore = ft_dir / ".gitignore"
    lines = []
    if gitignore.exists():
        lines = gitignore.read_text(encoding="utf-8").splitlines()
    if CREDENTIALS_FILENAME not in {ln.strip() for ln in lines}:
        lines.append(CREDENTIALS_FILENAME)
        gitignore.write_text("\n".join(lines) + "\n", encoding="utf-8")
    path = _credentials_path()
    if path.exists():
        os.chmod(path, 0o600)
