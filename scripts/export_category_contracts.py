"""Prepare anonymous category-directory HTTP contracts in explicitly new test databases."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import select

from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import CashCategoryModel, CashCategoryStateModel
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


def snapshot(sessions):
    with sessions() as session:
        return {
            "categories": sorted([[r.workspace_id, r.id, r.parent_id, r.parent_scope_key, r.name,
                r.normalized_name, r.description, r.category_path, r.depth, r.sort_order, r.revision]
                for r in session.scalars(select(CashCategoryModel))]),
            "states": sorted([[r.workspace_id, r.revision] for r in session.scalars(select(CashCategoryStateModel))]),
        }


def run(target_url, oracle_url, output):
    if target_url == oracle_url:
        raise ValueError("oracle and target must be distinct new dedicated test databases")
    export(target_url, output)
    export(oracle_url, output / "python")
    engine = create_relational_engine(oracle_url)
    sessions = create_session_factory(engine)
    workspace = ContextVar("category_workspace_oracle", default=None)
    service = WorkspaceServices(sessions, workspace)
    app = create_app(service, category_service=service, access_service=AccessService(sessions), workspace_context=workspace)
    cases = []
    ids = iter(f"category-created-{i}" for i in range(100))
    with patch("ft.application.cash_categories._now", return_value=datetime(2026, 10, 28, 14, tzinfo=timezone.utc)), \
            patch("ft.application.cash_categories.uuid4", side_effect=lambda: SimpleNamespace(hex=next(ids))), TestClient(app) as client:
        def request(method, path="", body=None, token="synthetic-admin"):
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            response = client.request(method, "/api/v1/cash-categories" + path, headers=headers,
                **({} if body is None else {"json": body}))
            cases.append({"method": method, "path": "/api/v1/cash-categories" + path,
                "request": body, "headers": headers, "status": response.status_code,
                "body": response.json(), "effects": snapshot(sessions)})
            return response.json()

        def revision():
            with sessions() as session:
                return session.get(CashCategoryStateModel, "fixture-a").revision

        def create(name, parent=None, **fields):
            return request("POST", body={"name": name, "parent_id": parent, "expected_revision": revision(), **fields})

        request("GET")
        for token in (None, "synthetic-viewer", "synthetic-removed", "synthetic-empty"):
            request("POST", body={"name": "拒绝"}, token=token)
        for name in ("", " \t\n", None, False, 0, [], {}, "x" * 41, "😀" * 41):
            create(name)
        root = create("  基础\t 餐饮  ", "root-z", description="  描述  ")
        child = create("子类", root["id"])
        for parent in ("root-a", None, "root-z"):
            request("PATCH", "/" + root["id"], {"parent_id": parent, "expected_revision": revision()})
        request("PATCH", "/root-z", {"parent_id": child["id"], "expected_revision": revision()})
        request("PATCH", "/" + root["id"], {"name": "", "parent_id": "root-a", "expected_revision": revision()})
        request("PATCH", "/" + root["id"], {"description": None, "expected_revision": revision()})
        request("PATCH", "/" + root["id"], {"description": "", "expected_revision": revision()})
        request("PATCH", "/" + root["id"], {"name": "重命名", "expected_revision": revision()})
        for name in ("Straße", "STRASSE", "Σ", "ς", "😀" * 40):
            create(name)
        create("完整描述", description="界" * 500)
        create("超长描述", description="界" * 501)
        create("错误父节点", "private")
        request("PATCH", "/missing", {"name": "不存在", "expected_revision": revision()})
        request("PATCH", "/" + root["id"], {"name": "不会写入", "expected_revision": revision() - 1})
        request("POST", "/root-a/reorder", {"direction": "after", "expected_revision": revision() - 1})
        request("POST", "/root-a/reorder", {"direction": "sideways", "expected_revision": revision()})
        request("POST", "/root-a/reorder", {"direction": "before", "expected_revision": revision()})
        request("POST", "/root-a/reorder", {"direction": "after", "expected_revision": revision()})
        request("POST", "/root-a/reorder", {"direction": "after", "expected_revision": revision()})
        current = child
        for name in ("四级", "五级", "六级"):
            created = create(name, current["id"])
            if "id" in created:
                current = created
        request("PATCH", "/" + root["id"], {"parent_id": current["id"], "expected_revision": revision()})
        request("GET")
    (output / "categories.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    engine.dispose()
    print(f"{len(cases)} category HTTP/effect contracts exported")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
