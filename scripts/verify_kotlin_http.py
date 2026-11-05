"""Compare an explicitly started loopback Kotlin server with anonymous Python oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.parse import urlparse

import httpx


def verify(origin: str, directory: Path) -> None:
    url = urlparse(origin)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1"} or url.port is None or url.path not in {"", "/"}:
        raise ValueError("explicit loopback test origin with port required")
    cases = json.loads((directory / "http.json").read_text())
    with httpx.Client(base_url=origin, trust_env=False, timeout=10) as client:
        for case in cases:
            result = client.request(case["method"], case["path"], headers=case["headers"])
            assert result.status_code == case["status"], (case["method"], case["path"], result.status_code)
            if "body" in case:
                assert result.json() == case["body"], (case["method"], case["path"])
            else:
                assert result.text == case["text"]
            for key, value in case.get("cors", {}).items():
                assert result.headers.get(key) == value, key
            assert "access-control-allow-credentials" not in result.headers
    print(f"{len(cases)} live HTTP oracle cases passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--fixtures", required=True, type=Path)
    arguments = parser.parse_args()
    verify(arguments.origin, arguments.fixtures)
