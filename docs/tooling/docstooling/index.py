"""Render the README index table from all reference documents."""

from __future__ import annotations

from .cells import escape_cell
from .document import Document

_DASH = "—"


def render_index(docs: list[Document]) -> str:
    rows = [
        "| ID | Title | Description | Tags |",
        "| -- | ----- | ----------- | ---- |",
    ]
    for doc in sorted(docs, key=lambda d: d.id):
        tags = ", ".join(escape_cell(t) for t in doc.tags) if doc.tags else _DASH
        title = escape_cell(doc.title)
        description = escape_cell(doc.description)
        rows.append(f"| [{doc.id}]({doc.path.name}) | {title} | {description} | {tags} |")
    return "\n".join(rows)
