"""Scaffold a new reference document from the template."""

from __future__ import annotations

import re
from pathlib import Path

from .config import DocType
from .document import load_all
from .frontmatter import render_frontmatter, split_frontmatter
from .markers import replace_region
from .sequence import next_id
from .sync import sync

_NON_SLUG = re.compile(r"[^a-z0-9]+")


def slugify(title: str) -> str:
    return _NON_SLUG.sub("-", title.lower()).strip("-")


def _scaffold_body(dt: DocType, title: str) -> str:
    _fm, body = split_frontmatter(dt.template_path.read_text(encoding="utf-8"))
    body = re.sub(r"^# .*$", f"# {title}", body, count=1, flags=re.MULTILINE)
    body = replace_region(body, dt.table_marker, "")
    return body.lstrip("\n")


def create(
    dt: DocType,
    *,
    title: str,
    description: str,
    tags: list[str],
    related: list[str],
    today: str,
) -> Path:
    docs = load_all(dt.root)
    new_id = next_id(docs, dt.id_width)
    path = dt.root / f"{new_id}-{slugify(title)}.md"
    frontmatter = render_frontmatter(
        {
            "id": new_id,
            "title": title,
            "description": description,
            "tags": tags,
            "created": today,
            "updated": today,
            "related": related,
        }
    )
    path.write_text(f"{frontmatter}\n{_scaffold_body(dt, title)}", encoding="utf-8")
    sync(dt)
    return path
