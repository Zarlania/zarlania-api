"""Render a reference doc's human-readable sister table from its fields."""

from __future__ import annotations

from .document import Document

_DASH = "—"


def _format_related(related: list[str], by_id: dict[str, Document]) -> str:
    if not related:
        return _DASH
    parts = []
    for rid in related:
        target = by_id.get(rid)
        parts.append(f"[{rid}]({target.path.name})" if target else rid)
    return ", ".join(parts)


def render_table(doc: Document, by_id: dict[str, Document]) -> str:
    tags = ", ".join(doc.tags) if doc.tags else _DASH
    rows = [
        "| Field | Value |",
        "| ----- | ----- |",
        f"| ID | {doc.id} |",
        f"| Title | {doc.title} |",
        f"| Description | {doc.description} |",
        f"| Tags | {tags} |",
        f"| Created | {doc.created} |",
        f"| Updated | {doc.updated} |",
        f"| Related | {_format_related(doc.related, by_id)} |",
    ]
    return "\n".join(rows)
