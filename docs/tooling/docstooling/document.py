"""The Document model and filesystem loading."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .frontmatter import FrontmatterError, split_frontmatter

REQUIRED_FIELDS: tuple[str, ...] = (
    "id",
    "title",
    "description",
    "tags",
    "created",
    "updated",
    "related",
)

# tags/related may legitimately be empty (null → []); a null scalar is a defect.
_NULLABLE_LIST_FIELDS = ("tags", "related")


@dataclass
class Document:
    id: str
    title: str
    description: str
    tags: list[str]
    created: str
    updated: str
    related: list[str]
    body: str
    path: Path


def load_document(path: Path) -> Document:
    data, body = split_frontmatter(path.read_text(encoding="utf-8"))
    missing = [f for f in REQUIRED_FIELDS if f not in data]
    null_scalars = [
        f
        for f in REQUIRED_FIELDS
        if f not in _NULLABLE_LIST_FIELDS and f in data and data[f] is None
    ]
    problems = sorted(set(missing) | set(null_scalars))
    if problems:
        raise FrontmatterError(
            f"{path.name}: missing or null frontmatter fields: {', '.join(problems)}"
        )
    for field in _NULLABLE_LIST_FIELDS:
        value = data[field]
        if value is not None and not (
            isinstance(value, list) and all(isinstance(item, str) for item in value)
        ):
            raise FrontmatterError(f"{path.name}: '{field}' must be null or a list of strings")
    return Document(
        id=str(data["id"]),
        title=str(data["title"]),
        description=str(data["description"]),
        tags=[str(t) for t in (data["tags"] or [])],
        created=str(data["created"]),
        updated=str(data["updated"]),
        related=[str(r) for r in (data["related"] or [])],
        body=body,
        path=path,
    )


def load_all(root: Path) -> list[Document]:
    """Load every reference doc (files whose name starts with a digit), sorted by id."""
    docs = [load_document(p) for p in root.glob("[0-9]*.md")]
    return sorted(docs, key=lambda d: d.id)
