"""Escape free-text values for safe rendering inside Markdown table cells."""

from __future__ import annotations


def escape_cell(value: str) -> str:
    """Neutralize characters that would break a Markdown table row.

    A literal ``|`` starts a new column and a line break ends the row, so both
    corrupt the generated table (and validation would render the same corruption
    and still pass). Pipes are escaped and any line break collapses to a space,
    keeping every cell on one line.
    """
    collapsed = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    return collapsed.replace("|", "\\|")
