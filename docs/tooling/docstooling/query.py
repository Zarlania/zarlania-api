"""Token-frugal queries over reference docs: frontmatter dump and search."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .config import DocType
from .document import Document, load_all

_META_FIELDS = ("id", "title", "description", "tags", "created", "updated", "related")


def meta(dt: DocType, only_id: str | None = None) -> list[dict[str, Any]]:
    docs = load_all(dt.root)
    if only_id is not None:
        docs = [d for d in docs if d.id == only_id]
    return [{field: getattr(d, field) for field in _META_FIELDS} for d in docs]


@dataclass
class SearchHit:
    id: str
    title: str
    filename: str
    snippet: str | None


def _body_snippet(doc: Document, needle: str) -> str | None:
    for line in doc.body.splitlines():
        if needle in line.lower():
            return line.strip()
    return None


def search(dt: DocType, query: str) -> list[SearchHit]:
    needle = query.lower()
    hits: list[SearchHit] = []
    for doc in load_all(dt.root):
        header = f"{doc.id} {doc.title} {doc.description} {' '.join(doc.tags)}".lower()
        if needle in header:
            hits.append(SearchHit(doc.id, doc.title, doc.path.name, None))
            continue
        snippet = _body_snippet(doc, needle)
        if snippet is not None:
            hits.append(SearchHit(doc.id, doc.title, doc.path.name, snippet))
    return hits
