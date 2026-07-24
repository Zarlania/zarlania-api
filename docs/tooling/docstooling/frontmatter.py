"""Parse and render the YAML frontmatter block of a markdown document."""

from __future__ import annotations

from typing import Any

import yaml

DELIMITER = "---"


class FrontmatterError(ValueError):
    """Raised when a document's frontmatter is missing or malformed."""


def split_frontmatter(text: str) -> tuple[dict[str, Any], str]:
    """Return ``(frontmatter, body)``.

    The document must start with a ``---`` line, contain a YAML mapping, and a
    closing ``---`` line. Everything after the closing delimiter is the body.
    """
    if not text.startswith(DELIMITER + "\n"):
        raise FrontmatterError("document does not start with '---' frontmatter")
    lines = text.split("\n")
    for i in range(1, len(lines)):
        if lines[i] == DELIMITER:
            raw = "\n".join(lines[1:i])
            body = "\n".join(lines[i + 1 :])
            try:
                loaded = yaml.safe_load(raw)
            except yaml.YAMLError as exc:
                raise FrontmatterError(f"frontmatter is not valid YAML: {exc}") from exc
            data = {} if loaded is None else loaded
            if not isinstance(data, dict):
                raise FrontmatterError("frontmatter is not a mapping")
            return data, body
    raise FrontmatterError("frontmatter closing '---' not found")


def render_frontmatter(data: dict[str, Any]) -> str:
    """Render a frontmatter mapping to a ``---``-delimited block (key order kept)."""
    dumped = yaml.safe_dump(data, sort_keys=False, allow_unicode=True).rstrip("\n")
    return f"{DELIMITER}\n{dumped}\n{DELIMITER}\n"
