"""Load the tag registry (the first column of the _tags.md table)."""

from __future__ import annotations

import re
from pathlib import Path

_ROW = re.compile(r"^\|\s*`?([A-Za-z0-9-]+)`?\s*\|")


def load_tags(path: Path) -> set[str]:
    tags: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = _ROW.match(line)
        if not match:
            continue
        token = match.group(1)
        if token.lower() == "tag" or set(token) == {"-"}:
            continue  # header cell or separator row
        tags.add(token)
    return tags
