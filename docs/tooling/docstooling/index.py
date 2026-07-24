"""Render the README index table from all reference documents."""

from __future__ import annotations

from .document import Document

_DASH = "—"


def render_index(docs: list[Document]) -> str:
    rows = [
        "| ID | Title | Description | Tags |",
        "| -- | ----- | ----------- | ---- |",
    ]
    for doc in sorted(docs, key=lambda d: d.id):
        tags = ", ".join(doc.tags) if doc.tags else _DASH
        rows.append(f"| [{doc.id}]({doc.path.name}) | {doc.title} | {doc.description} | {tags} |")
    return "\n".join(rows)
