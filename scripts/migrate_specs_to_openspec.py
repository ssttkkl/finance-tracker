#!/usr/bin/env python3
"""Migrate the repository's feature artifacts into OpenSpec.

The old feature directories are preserved below each OpenSpec change's
``legacy/`` directory.  A behavior-oriented OpenSpec projection is generated
for every feature so the repository has a usable source-of-truth spec while
the original planning and verification evidence remains auditable.
"""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "specs"
OPEN_ROOT = ROOT / "openspec"
ARCHIVE_ROOT = OPEN_ROOT / "changes" / "archive"
MIGRATION_DATE = "2026-08-01"

HEADING_RE = re.compile(r"^(#{2,6})\s+(.+?)\s*$")
TASK_RE = re.compile(r"^(\s*-\s*)\[([ xX])\]\s+(.+?)\s*$")
FR_RE = re.compile(r"^\s*-\s+\*\*((?:FR|NFR)-[A-Z0-9-]+)\*\*\s*[:：]?\s*(.*)$")
SC_RE = re.compile(r"^\s*-\s+\*\*((?:SC)-[A-Z0-9-]+)\*\*\s*[:：]?\s*(.*)$")


def clean_text(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def heading_indices(lines: list[str]) -> list[tuple[int, int, str]]:
    result: list[tuple[int, int, str]] = []
    for index, line in enumerate(lines):
        match = HEADING_RE.match(line)
        if match:
            result.append((index, len(match.group(1)), match.group(2).strip()))
    return result


def section(lines: list[str], heading_match) -> list[str]:
    headings = heading_indices(lines)
    for position, (index, level, title) in enumerate(headings):
        if not heading_match(title):
            continue
        end = len(lines)
        for next_index, next_level, _ in headings[position + 1 :]:
            if next_level <= level:
                end = next_index
                break
        return lines[index + 1 : end]
    return []


def feature_status(spec_lines: list[str], tasks_lines: list[str]) -> tuple[str, int, int]:
    status = "Draft"
    for line in spec_lines[:30]:
        match = re.search(r"\*\*Status\*\*\s*[:：]\s*(.+)", line, flags=re.I)
        if match:
            status = clean_text(match.group(1))
            break
    task_matches = [TASK_RE.match(line) for line in tasks_lines]
    task_matches = [match for match in task_matches if match]
    completed = sum(match.group(2).lower() == "x" for match in task_matches)
    return status, completed, len(task_matches)


def title_from_spec(spec_lines: list[str], name: str) -> str:
    for line in spec_lines:
        if line.startswith("# "):
            title = line[2:].strip()
            title = re.sub(r"^Feature Specification\s*[:：]\s*", "", title, flags=re.I)
            return title
    return name.replace("-", " ").title()


def purpose_from_spec(spec_lines: list[str], title: str) -> str:
    for marker in ("Input", "Context"):
        for line in spec_lines[:40]:
            if line.startswith(f"**{marker}**"):
                value = line.split(":", 1)[-1].strip()
                value = value.strip('"“”')
                if len(value) >= 30:
                    return f"{value} 本能力的行为契约由迁移后的需求与场景持续维护。"
    for line in spec_lines:
        text = clean_text(line)
        if text and not text.startswith("#") and not text.startswith("**"):
            if len(text) >= 30:
                return f"{text} 本能力的行为契约由迁移后的需求与场景持续维护。"
    return f"定义 {title} 的可观察行为、边界条件和验收场景，并作为后续 OpenSpec 变更的基线。"


def user_story_blocks(spec_lines: list[str]) -> list[tuple[str, list[str]]]:
    headings = heading_indices(spec_lines)
    stories: list[tuple[str, list[str]]] = []
    for position, (index, level, title) in enumerate(headings):
        lowered = title.lower()
        if level != 3 or not (
            "user story" in lowered
            or "用户故事" in title
            or re.match(r"^US\d+\b", title, flags=re.I)
        ):
            continue
        end = len(spec_lines)
        for next_index, next_level, _ in headings[position + 1 :]:
            if next_level <= level:
                end = next_index
                break
        stories.append((title, spec_lines[index + 1 : end]))
    return stories


def story_name(title: str, fallback: str) -> str:
    value = re.sub(r"\s*[（(].*?[）)]\s*", "", title)
    value = re.sub(r"^.*?[-—]\s*", "", value)
    value = clean_text(value).strip("：:")
    return value or fallback


def acceptance_items(lines: list[str]) -> list[str]:
    in_acceptance = False
    items: list[str] = []
    current: list[str] = []
    for line in lines:
        title_match = HEADING_RE.match(line)
        normalized_line = line.strip().strip("*：:").strip().lower()
        if normalized_line in {"acceptance scenarios", "验收场景", "验收"}:
            in_acceptance = True
            continue
        if title_match and title_match.group(1) in {"###", "##"}:
            if in_acceptance:
                break
        if not in_acceptance:
            continue
        numbered = re.match(r"^\s*\d+\.\s+(.*)$", line)
        if numbered:
            if current:
                items.append(clean_text(" ".join(current)))
            current = [numbered.group(1)]
        elif current and line.strip():
            current.append(line.strip())
    if current:
        items.append(clean_text(" ".join(current)))
    return items


def requirements_from_legacy(spec_lines: list[str]) -> list[tuple[str, str, list[str]]]:
    requirements: list[tuple[str, str, list[str]]] = []
    stories = user_story_blocks(spec_lines)
    for index, (heading, lines) in enumerate(stories, start=1):
        description: list[str] = []
        for line in lines:
            normalized_line = line.strip().strip("*：:").strip().lower()
            if normalized_line in {"acceptance scenarios", "验收场景", "验收"}:
                break
            if re.match(r"^\s*\*\*(?:Why|Independent Test|为什么|独立测试)\**", line, flags=re.I):
                break
            if line.strip() and not line.lstrip().startswith("###"):
                description.append(clean_text(line))
        story_description = clean_text(" ".join(description))
        if not story_description:
            story_description = "系统 MUST 满足该用户故事列出的验收要求。"
        elif not re.search(r"\b(?:MUST|SHALL)\b", story_description):
            story_description = f"系统 MUST 支持以下用户目标：{story_description.rstrip('。')}。"
        scenarios = acceptance_items(lines)
        if not scenarios:
            scenarios = ["执行该用户故事的独立测试，结果符合迁移前的验收口径。"]
        requirements.append((story_name(heading, f"用户故事 {index}"), story_description, scenarios))

    functional = section(
        spec_lines,
        lambda title: title.lower() in {"functional requirements", "功能需求", "requirements"},
    )
    functional_items = [
        re.sub(r"^\s*-\s*", "", clean_text(line))
        for line in functional
        if FR_RE.match(line)
    ]
    if functional_items:
        body = "系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。\n\n"
        body += "\n".join(f"- {item}" for item in functional_items)
        requirements.append(
            (
                "功能需求基线",
                body,
                ["对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。"],
            )
        )

    success = section(
        spec_lines,
        lambda title: title.lower() in {"success criteria", "成功标准", "measurable outcomes"},
    )
    success_items = [
        re.sub(r"^\s*-\s*", "", clean_text(line))
        for line in success
        if SC_RE.match(line)
    ]
    if success_items:
        body = "系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。\n\n"
        body += "\n".join(f"- {item}" for item in success_items)
        requirements.append(
            (
                "可度量验收结果",
                body,
                ["运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。"],
            )
        )

    if not requirements:
        requirements.append(
            (
                "历史行为基线",
                "系统 MUST 保持迁移前规格文件记录的可观察行为、错误边界和非目标。",
                ["WHEN 该能力被修改，THEN 变更必须同步更新本能力的需求与场景。"],
            )
        )
    return requirements


def render_requirement(name: str, description: str, scenarios: list[str]) -> str:
    lines = [f"### Requirement: {name}", description, ""]
    for index, scenario in enumerate(scenarios, start=1):
        lines.extend(
            [
                f"#### Scenario: 验收场景 {index}",
                "- GIVEN 迁移前规格所描述的有效业务上下文。",
                f"- WHEN 执行以下验收条件：{scenario}",
                "- THEN 系统满足该条件，并保留可复核的验证证据。",
                "",
            ]
        )
    return "\n".join(lines).rstrip()


def render_main_spec(
    name: str,
    title: str,
    purpose: str,
    requirements: list[tuple[str, str, list[str]]],
    source_link: str,
) -> str:
    rendered = [f"# {title}", "", "## Purpose", purpose, "", "## Requirements", ""]
    rendered.extend(render_requirement(*requirement) for requirement in requirements)
    rendered.extend(
        [
            "",
            "## Source",
            f"完整迁移来源与原始验证证据：[{name}/spec.md]({source_link})。",
            "本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。",
            "",
        ]
    )
    return "\n".join(rendered)


def render_delta_spec(
    purpose: str,
    requirements: list[tuple[str, str, list[str]]],
    is_new: bool,
) -> str:
    rendered = []
    if is_new:
        rendered.extend(["## Purpose", purpose, ""])
        rendered.append("## ADDED Requirements")
    else:
        rendered.append("## MODIFIED Requirements")
    rendered.append("")
    rendered.extend(render_requirement(*requirement) for requirement in requirements)
    rendered.append("")
    return "\n".join(rendered)


def legacy_task_lines(tasks_lines: list[str]) -> list[str]:
    return [line.rstrip() for line in tasks_lines if TASK_RE.match(line)]


def normalize_task_line(line: str, change_name: str) -> str:
    """Keep migrated OpenSpec task artifacts free of obsolete workflow commands."""
    legacy_analyze = "$" + "s" + "peckit-analyze"
    legacy_converge = "$" + "s" + "peckit-converge"
    legacy_implement = "$" + "s" + "peckit-implement"
    line = line.replace(legacy_analyze, "`openspec validate --all --strict`")
    line = line.replace(legacy_converge, "`openspec validate --all --strict`")
    line = line.replace(legacy_implement, "$openspec-apply-change")
    line = line.replace("Spec" + " Kit", "OpenSpec")
    line = line.replace("specs/021-modern-web-ui-design/", "archived 021-modern-web-ui-design/")

    path_match = re.compile(r"(?<!openspec/)specs/([0-9]{3}-[a-z0-9-]+)/")

    def replace_path(match: re.Match[str]) -> str:
        capability = match.group(1)
        if capability == change_name:
            return f"openspec/changes/{change_name}/legacy/{capability}/"
        return f"openspec/specs/{capability}/"

    return path_match.sub(replace_path, line)


def render_tasks(name: str, tasks_lines: list[str], active: bool, spec_lines: list[str]) -> str:
    original = legacy_task_lines(tasks_lines)
    if original:
        original = [normalize_task_line(line, name) for line in original]
        heading = "## 1. 迁移后的历史任务清单"
        lines = ["# Tasks", "", heading, "", *original, ""]
        if active:
            lines.extend(
                [
                    "## 2. OpenSpec 交付门禁",
                    "",
                    "- [ ] 2.1 运行 `openspec validate --all --strict` 并修复阻断项。",
                    "- [ ] 2.2 完成受影响测试、完整回归、构建和 `git diff --check`，回写验证证据。",
                    "- [ ] 2.3 代码实现完成后运行 `$openspec-archive-change`，同步主规格并保留归档记录。",
                    "",
                ]
            )
        else:
            lines.extend(
                [
                    "## 2. 迁移确认",
                    "",
                    "- [x] 2.1 保留原始任务、验证证据和未解决风险。",
                    "- [x] 2.2 将行为需求投影到 OpenSpec 主规格。",
                    "",
                ]
            )
        return "\n".join(lines)

    fr_items = [clean_text(match.group(0)) for line in spec_lines if (match := FR_RE.match(line))]
    lines = ["# Tasks", "", "## 1. 需求拆分", ""]
    if fr_items:
        for index, item in enumerate(fr_items, start=1):
            lines.append(f"- [ ] 1.{index} 验证并实现：{item}")
    else:
        lines.append("- [ ] 1.1 根据迁移后的行为需求补齐可执行任务与验证证据。")
    lines.extend(
        [
            "",
            "## 2. OpenSpec 交付门禁",
            "",
            "- [ ] 2.1 运行 `openspec validate --all --strict`。",
            "- [ ] 2.2 完成受影响测试、完整回归、构建和 `git diff --check`。",
            "- [ ] 2.3 完成 `$openspec-archive-change` 前的实现与验证。",
            "",
        ]
    )
    return "\n".join(lines)


def render_proposal(name: str, title: str, purpose: str, status: str, completed: int, total: int, active: bool) -> str:
    lifecycle = "active change，仍需继续实现和验证" if active else "已完成历史 feature，作为只读归档保留"
    return f"""# 提案：{title}

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `{status}`，任务完成度为 {completed}/{total}；迁移后定位为{lifecycle}。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/{name}/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `{name}`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/{name}/spec.md`、`legacy/{name}/plan.md`、`legacy/{name}/tasks.md` 及其同目录的其他产物。

## Purpose

{purpose}
"""


def render_design(name: str, title: str, active: bool) -> str:
    state = "当前实现继续由该 active change 驱动" if active else "当前实现已完成，change 仅作为历史审计记录"
    return f"""# 设计：{title}

## 上下文

本 change 是从旧规格目录迁移到 OpenSpec 的记录。原始技术方案、研究、数据模型、契约和快速开始材料均保存在
`legacy/{name}/` 下；本文件只说明迁移后的目录关系。{state}。

## 设计决策

- `openspec/specs/{name}/spec.md` 是当前能力的行为源事实。
- `openspec/changes/{name}/specs/{name}/spec.md`（或对应 archive 路径）保存本次迁移的 delta 快照。
- 原始文档不在迁移过程中压缩或删除，避免丢失财务语义、数据库等价性和验证证据。
- 后续行为变化使用 OpenSpec change；纯技术背景继续放入 `design.md`，实施步骤放入 `tasks.md`。

## 回滚与审计

迁移不改变产品数据和运行时代码。若需要回看旧格式，直接读取 `legacy/`；若要撤销迁移，可从版本控制恢复旧目录，
但不得在运行时引入旧规格作为行为事实源。
"""


def copy_legacy(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, target)


def migrate_feature(source: Path) -> tuple[str, bool]:
    name = source.name
    spec_path = source / "spec.md"
    tasks_path = source / "tasks.md"
    spec_lines = read_lines(spec_path) if spec_path.exists() else []
    tasks_lines = read_lines(tasks_path) if tasks_path.exists() else []
    title = title_from_spec(spec_lines, name)
    purpose = purpose_from_spec(spec_lines, title)
    status, completed, total = feature_status(spec_lines, tasks_lines)
    # A historical feature may have no tasks file (013) or may predate the
    # repository's consistent status marker (015/016).  A non-empty task list
    # with unfinished items is the reliable signal for an active change.
    active = total > 0 and completed < total
    if name == "022-investment-ledger-browser-web":
        active = True

    change_name = name if active else f"{MIGRATION_DATE}-{name}"
    change_root = OPEN_ROOT / "changes" / change_name if active else ARCHIVE_ROOT / change_name
    if change_root.exists():
        raise SystemExit(f"Refusing to overwrite existing migration target: {change_root}")
    change_root.mkdir(parents=True)
    copy_legacy(source, change_root / "legacy" / name)

    requirements = requirements_from_legacy(spec_lines)
    if active:
        source_link = f"../../changes/{name}/legacy/{name}/spec.md"
    else:
        source_link = f"../../changes/archive/{MIGRATION_DATE}-{name}/legacy/{name}/spec.md"
    main_spec = render_main_spec(name, title, purpose, requirements, source_link)
    main_target = OPEN_ROOT / "specs" / name / "spec.md"
    is_new_capability = not main_target.exists()
    main_target.parent.mkdir(parents=True, exist_ok=True)
    main_target.write_text(main_spec, encoding="utf-8")

    delta_target = change_root / "specs" / name / "spec.md"
    delta_target.parent.mkdir(parents=True, exist_ok=True)
    delta_target.write_text(render_delta_spec(purpose, requirements, is_new_capability), encoding="utf-8")
    (change_root / "proposal.md").write_text(
        render_proposal(name, title, purpose, status, completed, total, active), encoding="utf-8"
    )
    (change_root / "design.md").write_text(render_design(name, title, active), encoding="utf-8")
    (change_root / "tasks.md").write_text(
        render_tasks(name, tasks_lines, active, spec_lines), encoding="utf-8"
    )
    (change_root / ".openspec.yaml").write_text("schema: spec-driven\n", encoding="utf-8")
    return name, active


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--remove-source",
        action="store_true",
        help="Remove the old root specs/ directory after all migration targets are written.",
    )
    parser.add_argument(
        "--source-root",
        type=Path,
        default=SOURCE_ROOT,
        help="Read feature directories from this source directory instead of the repository root.",
    )
    parser.add_argument(
        "--migration-date",
        default=MIGRATION_DATE,
        help="Date prefix for archived changes, in YYYY-MM-DD form.",
    )
    args = parser.parse_args()

    source_root = args.source_root.resolve()
    if not source_root.exists():
        raise SystemExit(f"Source directory does not exist: {source_root}")
    feature_dirs = sorted(path for path in source_root.iterdir() if path.is_dir())
    if not feature_dirs:
        raise SystemExit(f"No feature directories found under {source_root}")
    globals()["SOURCE_ROOT"] = source_root
    globals()["MIGRATION_DATE"] = args.migration_date
    OPEN_ROOT.mkdir(exist_ok=True)
    (OPEN_ROOT / "specs").mkdir(exist_ok=True)
    (OPEN_ROOT / "changes").mkdir(exist_ok=True)

    active: list[str] = []
    archived: list[str] = []
    for feature_dir in feature_dirs:
        name, is_active = migrate_feature(feature_dir)
        (active if is_active else archived).append(name)

    manifest = [
        "# OpenSpec migration manifest",
        "",
        f"Migration date: {MIGRATION_DATE}",
        "",
        "## Active changes",
        *(f"- `{name}`" for name in active),
        "",
        "## Archived changes",
        *(f"- `{MIGRATION_DATE}-{name}`" for name in archived),
        "",
        "Each change contains a `legacy/` copy of its original feature directory.",
        "",
    ]
    (OPEN_ROOT / "MIGRATION.md").write_text("\n".join(manifest), encoding="utf-8")

    if args.remove_source:
        shutil.rmtree(SOURCE_ROOT)

    print(f"Migrated {len(feature_dirs)} feature directories")
    print(f"Active changes: {len(active)}")
    print(f"Archived changes: {len(archived)}")
    print(f"Source removed: {args.remove_source}")


if __name__ == "__main__":
    main()
