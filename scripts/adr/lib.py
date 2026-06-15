"""Library for managing Architecture Decision Records (ADRs)."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml

# Ordered ADR metadata fields mapped to their human-readable table labels.
FIELD_LABELS: dict[str, str] = {
    "id": "ID",
    "name": "Name",
    "description": "Description",
    "status": "Status",
    "date_proposed": "Date proposed",
    "date_accepted": "Date accepted",
    "date_invalidated": "Date invalidated",
    "author": "Author",
    "supersedes": "Supersedes",
    "superseded_by": "Superseded by",
    "tags": "Tags",
}

VALID_STATUSES = {"proposed", "accepted", "superseded", "deprecated", "rejected"}
LIST_FIELDS = {"supersedes", "superseded_by", "tags"}

FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n?(.*)$", re.DOTALL)


def slugify(name: str) -> str:
    """Lowercase, hyphenate, strip to a filename-safe slug."""
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


@dataclass
class Adr:
    path: Path
    frontmatter: dict
    body: str


def parse_adr(path) -> "Adr":
    text = Path(path).read_text(encoding="utf-8")
    m = FRONTMATTER_RE.match(text)
    if not m:
        raise ValueError(f"{path}: missing or malformed YAML frontmatter")
    fm = yaml.safe_load(m.group(1)) or {}
    return Adr(path=Path(path), frontmatter=fm, body=m.group(2))


def dump_frontmatter(fm: dict) -> str:
    """Serialize frontmatter in canonical field order (only known fields)."""
    ordered = {key: fm.get(key) for key in FIELD_LABELS}
    return yaml.safe_dump(ordered, sort_keys=False, allow_unicode=True).rstrip()
