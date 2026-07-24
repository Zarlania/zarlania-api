"""Regenerate sister tables and the README index from frontmatter."""

from __future__ import annotations

from .config import DocType
from .document import load_all
from .index import render_index
from .markers import replace_region
from .table import render_table


def sync(dt: DocType) -> None:
    docs = load_all(dt.root)
    by_id = {d.id: d for d in docs}
    for doc in docs:
        text = doc.path.read_text(encoding="utf-8")
        updated = replace_region(text, dt.table_marker, render_table(doc, by_id))
        if updated != text:
            doc.path.write_text(updated, encoding="utf-8")

    readme = dt.readme_path.read_text(encoding="utf-8")
    updated_readme = replace_region(readme, dt.index_marker, render_index(docs))
    if updated_readme != readme:
        dt.readme_path.write_text(updated_readme, encoding="utf-8")
