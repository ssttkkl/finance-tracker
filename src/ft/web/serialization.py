"""Web API 的安全 JSON 序列化。"""
from __future__ import annotations

from ft.serialization import json_value


def error_payload(code: str, message: str, **details) -> dict:
    return {"error": {"code": code, "message": message}, **details}
