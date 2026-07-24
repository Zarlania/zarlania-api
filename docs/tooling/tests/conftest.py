"""Shared fixtures: a minimal, valid references directory in a tmp path."""

from __future__ import annotations

from pathlib import Path

import pytest

from docstooling.config import DocType, reference_doctype

_TAGS = """# Reference tags

<!-- reference-tags -->
| Tag | Description |
| --- | ----------- |
| controllers | Spring MVC controllers. |
| http | HTTP request/response handling. |
"""

_README = """# References

<!-- reference-index:start -->
<!-- reference-index:end -->
"""

_TEMPLATE = """---
id: "000000"
title: Title here
description: One-line description.
tags: []
created: 2026-01-01
updated: 2026-01-01
related: []
---

# Title here

<!-- reference-table:start -->
<!-- reference-table:end -->

Documentation prose goes here.
"""


def write_doc(
    root: Path,
    doc_id: str,
    slug: str,
    *,
    title: str,
    tags: list[str],
    related: list[str],
    created: str = "2026-07-23",
    updated: str = "2026-07-23",
) -> Path:
    """Write a reference doc with an (empty) table region for tests to sync/validate."""
    tags_yaml = "[" + ", ".join(tags) + "]"
    related_yaml = "[" + ", ".join(f'"{r}"' for r in related) + "]"
    path = root / f"{doc_id}-{slug}.md"
    path.write_text(
        f"---\n"
        f'id: "{doc_id}"\n'
        f"title: {title}\n"
        f"description: Desc for {title}.\n"
        f"tags: {tags_yaml}\n"
        f"created: {created}\n"
        f"updated: {updated}\n"
        f"related: {related_yaml}\n"
        f"---\n\n"
        f"# {title}\n\n"
        f"<!-- reference-table:start -->\n<!-- reference-table:end -->\n\n"
        f"Body of {title}.\n",
        encoding="utf-8",
    )
    return path


@pytest.fixture
def reference_root(tmp_path: Path) -> Path:
    root = tmp_path / "references"
    root.mkdir()
    (root / "_tags.md").write_text(_TAGS, encoding="utf-8")
    (root / "README.md").write_text(_README, encoding="utf-8")
    (root / "_template.md").write_text(_TEMPLATE, encoding="utf-8")
    return root


@pytest.fixture
def reference_dt(reference_root: Path) -> DocType:
    return reference_doctype(reference_root)
