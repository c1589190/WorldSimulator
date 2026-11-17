#!/usr/bin/env python3
"""WorldSimulator 文档结构检查（最小机械门禁）。

只检查可机械判定的内容，不冒充设计评审：
- 必需入口文件存在；
- STATUS 含关键字段；
- 设计/报告目录下的 Markdown 含状态头；
- 文档内相对 Markdown 链接可解析；
- 旧调研/草案含“历史/取代”状态提示。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

REQUIRED = [
    ROOT / "AGENTS.md",
    DOCS / "AGENTS.md",
    DOCS / "README.md",
    DOCS / "agents" / "STATUS.md",
    DOCS / "agents" / "MAINTENANCE_RULINGS.md",
    DOCS / "superpowers" / "AGENTS.md",
    DOCS / "superpowers" / "DESIGN_RULINGS.md",
    DOCS / "superpowers" / "specs" / "2026-11-17-worldsim-architecture-design-source.md",
    DOCS / "superpowers" / "specs" / "2026-11-17-map-orbat-info-timeline-design.md",
    DOCS / "superpowers" / "reports" / "2026-11-17-neighbor-projects-recon.md",
    DOCS / "superpowers" / "reports" / "2026-11-17-renderer-options.md",
]

STATUS_FIELDS = ["更新时间", "当前目标", "未验证", "下一步"]

REL_LINK = re.compile(r"\]\((?P<target>\.{0,2}/[^)\s#]+)")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def check_required(errors: list[str]) -> None:
    for path in REQUIRED:
        if not path.is_file():
            fail(errors, f"缺少必需文件: {path.relative_to(ROOT)}")


def check_status(errors: list[str]) -> None:
    path = DOCS / "agents" / "STATUS.md"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    for field in STATUS_FIELDS:
        if field not in text:
            fail(errors, f"STATUS 缺少字段「{field}」")


def check_status_headers(errors: list[str]) -> None:
    for folder in (DOCS / "superpowers" / "specs", DOCS / "superpowers" / "reports"):
        if not folder.is_dir():
            fail(errors, f"缺少目录: {folder.relative_to(ROOT)}")
            continue
        for path in sorted(folder.glob("*.md")):
            text = path.read_text(encoding="utf-8")
            if "> **状态**" not in text:
                fail(errors, f"{path.relative_to(ROOT)} 缺少 `> **状态**` 状态头")
            if "> **本文性质**" not in text:
                fail(errors, f"{path.relative_to(ROOT)} 缺少 `> **本文性质**`")


def check_legacy_headers(errors: list[str]) -> None:
    for name in ("hoi4-community-map-resources-survey.md", "agent-world-model-draft.md"):
        path = DOCS / name
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if "历史" not in text or "取代者" not in text:
            fail(errors, f"{path.relative_to(ROOT)} 缺少历史/取代状态提示")


def check_links(errors: list[str]) -> None:
    for path in sorted(DOCS.rglob("*.md")):
        text = path.read_text(encoding="utf-8")
        for match in REL_LINK.finditer(text):
            target = match.group("target").rstrip(".,;")
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                fail(errors, f"{path.relative_to(ROOT)} 的相对链接不存在: {target}")


def main() -> int:
    errors: list[str] = []
    check_required(errors)
    check_status(errors)
    check_status_headers(errors)
    check_legacy_headers(errors)
    check_links(errors)
    if errors:
        print("DOC CHECK: FAIL")
        for error in errors:
            print(" -", error)
        return 1
    print("DOC CHECK: PASS")
    print(f" checked {len(REQUIRED)} required files, "
          f"{len(list((DOCS / 'superpowers' / 'specs').glob('*.md')))} specs, "
          f"{len(list((DOCS / 'superpowers' / 'reports').glob('*.md')))} reports")
    return 0


if __name__ == "__main__":
    sys.exit(main())
