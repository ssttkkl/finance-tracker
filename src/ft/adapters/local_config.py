"""Local YAML-backed configuration adapters."""
from pathlib import Path


class LocalMappingProvider:
    def __init__(self, ledger_root):
        self._path = Path(ledger_root) / "mapping.yaml"

    def get_mapping(self, name):
        if name.startswith("sync:"):
            return None
        if name != "cashflow":
            return None
        from ft.mapping import load_rules

        return load_rules(self._path)
