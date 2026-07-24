"""Replace and extract HTML-comment-delimited regions in markdown."""

from __future__ import annotations


class MarkerError(ValueError):
    """Raised when a named region's markers are missing or malformed."""


def _markers(name: str) -> tuple[str, str]:
    return f"<!-- {name}:start -->", f"<!-- {name}:end -->"


def _bounds(text: str, name: str) -> tuple[str, int, str, int]:
    start, end = _markers(name)
    si = text.find(start)
    ei = text.find(end)
    if si == -1 or ei == -1 or ei < si:
        raise MarkerError(f"region '{name}' markers not found or malformed")
    return start, si, end, ei


def replace_region(text: str, name: str, content: str) -> str:
    """Replace the content between the start and end markers with ``content``."""
    start, si, _end, ei = _bounds(text, name)
    prefix = text[: si + len(start)]
    suffix = text[ei:]
    return f"{prefix}\n{content}\n{suffix}"


def extract_region(text: str, name: str) -> str:
    """Return the current content between the markers, stripped of edge newlines."""
    start, si, _end, ei = _bounds(text, name)
    return text[si + len(start) : ei].strip("\n")
