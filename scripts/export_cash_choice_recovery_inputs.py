"""Prepare original anonymous choice recovery files and upload inputs."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_business_plan import _mirror_files
from tests.contract.test_cash_import_choice_recovery import _extra
from tests.contract.test_parsed_cash_import_context import USER, _upload


SCENARIOS = tuple(f"{action}-{choice}" for action in ("add", "replace", "remove", "continuous")
    for choice in ("defer", "ignore")) + ("member", "failure-build", "failure-publish", "cancel")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    files = _mirror_files() + (_extra(),)
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    values = {}
    for name, items in {"default": files, "extra": (_extra(),)}.items():
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=items)
        values[name] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    upload = _upload()
    values["upload"] = {"filename": upload.filename, "content": upload.content.decode(),
        "password": upload.password, "source_hint": upload.source_hint}
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
