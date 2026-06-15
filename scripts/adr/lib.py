"""Library for managing Architecture Decision Records (ADRs)."""
from __future__ import annotations

import re

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


def slugify(name: str) -> str:
    """Lowercase, hyphenate, strip to a filename-safe slug."""
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
