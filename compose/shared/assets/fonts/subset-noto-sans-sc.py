#!/usr/bin/env python3
"""Build the Web-only WOFF2 with GB2312 and all static Compose UI glyphs."""

from pathlib import Path
import argparse

from fontTools import subset
from fontTools.ttLib import TTFont


ROOT = Path(__file__).resolve().parents[4]


def gb2312_codepoints() -> set[int]:
    points = set(range(0x20, 0x7F))
    points.update(range(0x3000, 0x3040))
    for lead in range(0xA1, 0xF8):
        for trail in range(0xA1, 0xFF):
            try:
                points.update(map(ord, bytes((lead, trail)).decode("gb2312")))
            except UnicodeDecodeError:
                pass
    return points


def compose_ui_codepoints() -> set[int]:
    roots = [
        ROOT / "compose/shared/src/commonMain",
        ROOT / "compose/shared/src/commonTest",
        ROOT / "web/tests/compose-fixtures.ts",
    ]
    points: set[int] = set()
    for root in roots:
        files = [root] if root.is_file() else root.rglob("*")
        for file in files:
            if file.is_file() and file.suffix in {".kt", ".ts"}:
                points.update(map(ord, file.read_text(encoding="utf-8")))
    return points


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_ttf", type=Path)
    parser.add_argument("output_woff2", type=Path)
    args = parser.parse_args()

    options = subset.Options()
    options.flavor = "woff2"
    options.hinting = False
    options.layout_features = ["*"]
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    options.name_legacy = True
    options.name_languages = [0x409]

    font = TTFont(args.input_ttf)
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=gb2312_codepoints() | compose_ui_codepoints())
    subsetter.subset(font)
    font.flavor = "woff2"
    args.output_woff2.parent.mkdir(parents=True, exist_ok=True)
    font.save(args.output_woff2)
    print(f"Wrote {args.output_woff2} ({args.output_woff2.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
