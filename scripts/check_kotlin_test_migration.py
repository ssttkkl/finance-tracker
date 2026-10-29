"""Check the baseline Python test inventory against reviewed Kotlin test mappings."""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
BASELINE = "0c038a75e34264bd6d4aa5cce2070727df9d9ff7"
SOURCE_ROOTS = ["tests", "compose/tests"]
ROOT_CONFIGS = ["pyproject.toml", "pytest.ini", "setup.cfg", "tox.ini"]


def definitions(source: str, path: str) -> dict[str, str]:
    result = {}

    def visit(body, parents=()):
        for node in body:
            if isinstance(node, ast.ClassDef):
                visit(node.body, (*parents, node.name))
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
                key = "::".join((path, *parents, node.name))
                if key in result:
                    raise ValueError(f"Duplicate Python definition: {key}")
                result[key] = hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()

    visit(ast.parse(source).body)
    return result


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)


def input_fingerprint(source: bytes, path: str) -> str:
    if path.endswith(".py"):
        tree = ast.parse(source)

        def remove_tests(body):
            result = []
            for node in body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
                    continue
                if isinstance(node, ast.ClassDef):
                    node.body = remove_tests(node.body)
                result.append(node)
            return result

        if Path(path).name.startswith("test_"):
            tree.body = remove_tests(tree.body)
        source = ast.dump(tree, include_attributes=False).encode()
    return hashlib.sha256(source).hexdigest()


def input_inventory(baseline: str, roots: list[str]):
    original, current = {}, {}
    paths = git("ls-tree", "-r", "--name-only", baseline, *roots, *ROOT_CONFIGS).splitlines()
    for path in paths:
        source = subprocess.check_output(["git", "show", f"{baseline}:{path}"], cwd=ROOT)
        original[path] = input_fingerprint(source, path)
    current_paths = {ROOT / path for path in [*paths, *ROOT_CONFIGS]}
    for source_root in roots:
        current_paths.update(path for path in (ROOT / source_root).rglob("*")
            if path.is_file() and "__pycache__" not in path.parts and ".pytest_cache" not in path.parts)
    for path in sorted(current_paths):
        if path.is_file():
            current[path.relative_to(ROOT).as_posix()] = input_fingerprint(path.read_bytes(), path.name)
    return original, current


def check_inputs(original: dict[str, str], current: dict[str, str], reviewed: dict):
    errors = []
    for path in sorted(original.keys() | current.keys()):
        if original.get(path) != current.get(path):
            change = reviewed.get(path, {})
            if (change.get("baseline_sha256") != original.get(path) or change.get("current_sha256") != current.get(path)
                    or not change.get("reason")):
                errors.append(f"Test parameter source/fixture/config changed without exact review: {path}")
    for path in reviewed:
        if path not in original.keys() | current.keys() or original.get(path) == current.get(path):
            errors.append(f"Stale/unknown reviewed input change: {path}")
    return errors


def inventory(baseline: str, roots: list[str]) -> tuple[dict[str, str], dict[str, str]]:
    original, current = {}, {}
    paths = git("ls-tree", "-r", "--name-only", baseline, *roots).splitlines()
    for path in paths:
        if Path(path).name.startswith("test_") and path.endswith(".py"):
            original.update(definitions(git("show", f"{baseline}:{path}"), path))
    for source_root in roots:
        for path in sorted((ROOT / source_root).rglob("test_*.py")):
            current.update(definitions(path.read_text(), path.relative_to(ROOT).as_posix()))
    return original, current


def check(original: dict[str, str], current: dict[str, str], entries: list[dict], root: Path,
          source_changes: dict | None = None, source_additions: dict | None = None):
    errors, seen, states = [], set(), {}
    source_changes = source_changes or {}
    source_additions = source_additions or {}
    reviewed_sources = dict(original)
    for key in sorted(original.keys() | current.keys()):
        states[key] = "pending"
        if key not in original:
            addition = source_additions.get(key, {})
            if addition.get("source_sha256") != current[key] or not addition.get("reason"):
                errors.append(f"New Python test needs explicit inventory review: {key}")
            else:
                reviewed_sources[key] = current[key]
        elif key not in current:
            errors.append(f"Baseline Python test was removed: {key}")
        elif original[key] != current[key]:
            change = source_changes.get(key, {})
            if (change.get("baseline_sha256") != original[key] or change.get("current_sha256") != current[key]
                    or not change.get("reason")):
                errors.append(f"Python test changed since baseline without an exact reviewed change: {key}")
    for key in source_changes:
        if key not in original or key not in current or original[key] == current[key]:
            errors.append(f"Stale/unknown reviewed source change: {key}")
    for key in source_additions:
        if key in original or key not in current:
            errors.append(f"Stale/unknown reviewed source addition: {key}")
    for entry in entries:
        key = entry["source"]
        if key in seen:
            errors.append(f"Duplicate mapping: {key}")
        seen.add(key)
        if key not in reviewed_sources:
            errors.append(f"Unknown baseline or reviewed added test: {key}")
            continue
        if entry.get("source_sha256") != reviewed_sources[key]:
            errors.append(f"Stale source fingerprint: {key}")
        if not entry.get("assertions") or not entry.get("parameters"):
            errors.append(f"Missing assertion/parameter review: {key}")
        status = entry.get("status")
        if status not in {"migrated", "retained", "pending"}:
            errors.append(f"Unknown mapping status: {key}")
            continue
        targets = entry.get("targets", [])
        if status == "migrated" and not targets:
            errors.append(f"No Kotlin compatibility test: {key}")
        for target in targets:
            path = root / target["path"]
            if not path.resolve().is_relative_to(root.resolve()) or not path.is_file():
                errors.append(f"Missing/invalid Kotlin test file: {key}: {target['path']}")
                continue
            pattern = r"@Test\s+(?:public\s+)?fun\s+" + re.escape(target["test"]) + r"\s*\("
            if not re.search(pattern, path.read_text()):
                errors.append(f"Missing Kotlin @Test: {key}: {target['test']}")
        if status == "retained" and not all(entry.get(field) for field in ("reason", "python_command", "compatibility")):
            errors.append(f"Retained test needs reason, execution command and compatibility review: {key}")
        if status == "pending" and not entry.get("reason"):
            errors.append(f"Pending mapping needs remaining work: {key}")
        states[key] = status
    return errors, states


def check_parameters(entries: list[dict], nodes: list[str]):
    collected = {}
    for node in nodes:
        collected.setdefault(node.split("[", 1)[0], []).append(node)
    return [f"Mapped parameters differ from the complete collected case set: {entry['source']}"
        for entry in entries if Counter(entry.get("parameters", [])) != Counter(collected.get(entry["source"], []))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--map", type=Path, default=ROOT / "compose/test-migration.json")
    parser.add_argument("--collection", type=Path, help="Unfiltered pytest --collect-only -q output")
    parser.add_argument("--scope", action="append", default=[], help="Exact source file path; repeatable")
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--report", type=Path, help="Write the full inventory outside the repository")
    args = parser.parse_args()
    mapping = json.loads(args.map.read_text())
    if mapping.get("schema_version") != 1:
        raise ValueError("Unsupported test migration map schema")
    if mapping["baseline"] != BASELINE or mapping["source_roots"] != SOURCE_ROOTS:
        raise ValueError("The migration baseline and full source roots cannot be narrowed through map metadata")
    original, current = inventory(mapping["baseline"], mapping["source_roots"])
    errors, states = check(original, current, mapping["entries"], ROOT, mapping.get("source_changes"), mapping.get("source_additions"))
    original_inputs, current_inputs = input_inventory(mapping["baseline"], mapping["source_roots"])
    errors.extend(check_inputs(original_inputs, current_inputs, mapping.get("input_changes", {})))
    nodes = []
    if args.require_complete and not args.collection:
        errors.append("Completion requires an unfiltered pytest collection")
    if args.collection:
        collection_text = args.collection.read_text()
        for line in collection_text.splitlines():
            if "::" in line and line.split("::", 1)[0].endswith(".py"):
                nodes.append(line)
        if not nodes or len(nodes) != len(set(nodes)):
            errors.append("Collection must contain unique, unfiltered pytest node IDs")
        summary = re.search(r"(\d+) tests? collected", collection_text)
        if not summary or int(summary[1]) != len(nodes):
            errors.append("Collection node count must match pytest's successful collection summary")
        errors.extend(check_parameters(mapping["entries"], nodes))
        collected = {node.split("[", 1)[0] for node in nodes}
        if collected != current.keys():
            errors.append(f"Collection differs from current source definitions: missing={sorted(current.keys()-collected)}, extra={sorted(collected-current.keys())}")
    scoped = {key: state for key, state in states.items() if not args.scope or key.split("::", 1)[0] in args.scope}
    if args.scope and not scoped:
        errors.append("Scope matched no Python tests")
    counts = Counter(scoped.values())
    print(json.dumps({"baseline": mapping["baseline"], "source_definitions": len(scoped),
        "collected_cases": len(nodes) if args.collection else None, "status": dict(counts), "errors": errors}, ensure_ascii=False, indent=2))
    if args.report:
        if args.report.resolve().is_relative_to(ROOT.resolve()):
            raise ValueError("Generated test reports must stay outside the repository")
        args.report.write_text(json.dumps({"tests": scoped, "pytest_nodes": nodes, "errors": errors}, ensure_ascii=False, indent=2) + "\n")
    return 1 if errors or (args.require_complete and counts["pending"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
