"""Parse and render the YAML frontmatter block of a markdown document."""

from __future__ import annotations

from typing import Any

import yaml

DELIMITER = "---"


class FrontmatterError(ValueError):
    """Raised when a document's frontmatter is missing or malformed."""


class _UniqueKeyLoader(yaml.SafeLoader):
    """A SafeLoader that rejects duplicate mapping keys.

    PyYAML's default loader silently keeps the last value when a key repeats, so
    a hand-edited or conflict-resolved doc could regenerate its table and index
    from overwritten metadata. yamllint's duplicate-key rule does not reach YAML
    embedded in Markdown frontmatter, so the loader enforces it here.
    """


def _construct_mapping_no_duplicates(
    loader: _UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if not isinstance(key, str):
            raise yaml.constructor.ConstructorError(
                None,
                None,
                f"frontmatter keys must be strings, got {type(key).__name__}",
                key_node.start_mark,
            )
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                None, None, f"duplicate frontmatter key: {key!r}", key_node.start_mark
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_mapping_no_duplicates
)


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
                loaded = yaml.load(raw, Loader=_UniqueKeyLoader)
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
