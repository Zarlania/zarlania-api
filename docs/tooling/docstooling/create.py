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
from .tags import load_tags

_NON_SLUG = re.compile(r"[^a-z0-9]+")


def slugify(title: str) -> str:
    slug = _NON_SLUG.sub("-", title.lower()).strip("-")
    return slug or "untitled"


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
    # Reject inputs validate() would later reject, so create never writes a
    # document that immediately fails the structural check.
    unknown_tags = sorted(set(tags) - load_tags(dt.tags_path))
    if unknown_tags:
        raise ValueError(
            f"unknown tags (register in {dt.tags_path.name} first): {', '.join(unknown_tags)}"
        )
    known_ids = {doc.id for doc in docs} | {new_id}
    unknown_related = sorted(set(related) - known_ids)
    if unknown_related:
        raise ValueError(f"unknown related ids: {', '.join(unknown_related)}")
    path = dt.root / f"{new_id}-{slugify(title)}.md"
    frontmatter = render_frontmatter(
        {
            "id": new_id,
            "title": title,
            "description": description,
            "tags": sorted(tags),
            "created": today,
            "updated": today,
            "related": related,
        }
    )
    path.write_text(f"{frontmatter}\n{_scaffold_body(dt, title)}", encoding="utf-8")
    sync(dt)
    return path
