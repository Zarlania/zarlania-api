"""Shared core for numbered, frontmatter-driven document tools (ADRs, reference docs).

Both ``adr_tool`` and ``ref_tool`` build on these generic primitives; each tool supplies
its own schema (field labels, list fields, meta markers), id width, index renderer, and any
schema-specific validation extras. This module knows nothing about ADRs vs reference docs.
"""

from __future__ import annotations

import datetime as _dt
import re
from dataclasses import dataclass
from pathlib import Path

import yaml

EMPTY_DISPLAY = "—"

FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n?(.*)$", re.DOTALL)
_TAG_ROW_RE = re.compile(r"^\|\s*(?P<tag>[^|]+?)\s*\|\s*(?P<desc>.*?)\s*\|\s*$")
# A markdown table separator row (e.g. "| --- | --- |"): only pipes, spaces, hyphens.
_TAG_SEP_RE = re.compile(r"^\|[\s\-|]+\|\s*$")


def slugify(name: str) -> str:
    """Lowercase, hyphenate, strip to a filename-safe slug."""
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def today_iso() -> str:
    return _dt.date.today().isoformat()


@dataclass
class Doc:
    path: Path
    frontmatter: dict
    body: str


def parse_doc(path) -> Doc:
    text = Path(path).read_text(encoding="utf-8")
    m = FRONTMATTER_RE.match(text)
    if not m:
        raise ValueError(f"{path}: missing or malformed YAML frontmatter")
    try:
        fm = yaml.safe_load(m.group(1)) or {}
    except yaml.YAMLError as exc:
        # Surface a YAML parse failure as a domain ValueError so validate_common
        # reports it instead of crashing the `check` command with a traceback.
        raise ValueError(f"{path}: invalid YAML frontmatter: {exc}") from exc
    if not isinstance(fm, dict):
        raise ValueError(f"{path}: frontmatter must be a mapping")
    return Doc(path=Path(path), frontmatter=fm, body=m.group(2))


def dump_frontmatter(fm: dict, field_order) -> str:
    """Serialize frontmatter in the given field order (only known fields)."""
    ordered = {key: fm.get(key) for key in field_order}
    return yaml.safe_dump(ordered, sort_keys=False, allow_unicode=True).rstrip()


def display_value(value, is_list: bool) -> str:
    if value is None or value == "" or value == []:
        return EMPTY_DISPLAY
    if is_list:
        if isinstance(value, list):
            return ", ".join(str(v) for v in value)
        return str(value)
    return str(value)


def render_meta_table(fm: dict, field_labels: dict, list_fields, start: str, end: str) -> str:
    lines = [start, "| Field | Value |", "| --- | --- |"]
    for key, label in field_labels.items():
        lines.append(f"| {label} | {display_value(fm.get(key), key in list_fields)} |")
    lines.append(end)
    return "\n".join(lines)


def extract_meta_table(body: str, start: str, end: str) -> str | None:
    s = body.find(start)
    e = body.find(end)
    if s == -1 or e == -1 or e < s:
        return None
    return body[s : e + len(end)]


def _glob(width: int) -> str:
    return "[0-9]" * width + "-*.md"


def _id_prefix_re(width: int):
    return re.compile(rf"^(\d{{{width}}})-.*\.md$")


def next_id(doc_dir, width: int) -> str:
    rx = _id_prefix_re(width)
    nums = []
    for p in Path(doc_dir).glob(_glob(width)):
        m = rx.match(p.name)
        if m:
            nums.append(int(m.group(1)))
    return f"{(max(nums) + 1) if nums else 1:0{width}d}"


def iter_docs(doc_dir, width: int) -> list[Doc]:
    return [parse_doc(p) for p in sorted(Path(doc_dir).glob(_glob(width)))]


def load_tags(tags_path) -> dict[str, str]:
    tags: dict[str, str] = {}
    for line in Path(tags_path).read_text(encoding="utf-8").splitlines():
        m = _TAG_ROW_RE.match(line)
        if not m:
            continue
        tag = m.group("tag").strip()
        desc = m.group("desc").strip()
        if tag.lower() == "tag" or set(tag) <= {"-"}:
            continue  # header / separator rows
        tags[tag] = desc
    return tags


def _registry_header(text: str) -> str:
    """Return the registry prose + table header up to and including the separator row."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if _TAG_SEP_RE.match(line):
            return "\n".join(lines[: i + 1])
    raise ValueError("tag registry missing table header separator row")


def add_tag(tags_path, tag: str, description: str) -> None:
    if "|" in tag or "\n" in tag:
        raise ValueError("tag must not contain '|' or newline characters")
    if "|" in description or "\n" in description:
        raise ValueError("description must not contain '|' or newline characters")
    tags_path = Path(tags_path)
    tags = load_tags(tags_path)
    if tag in tags:
        return
    tags[tag] = description
    # Rewrite the table with rows in alphabetical order.
    header = _registry_header(tags_path.read_text(encoding="utf-8"))
    rows = "\n".join(f"| {t} | {tags[t]} |" for t in sorted(tags))
    tags_path.write_text(f"{header}\n{rows}\n", encoding="utf-8")


def write_index(doc_dir, index_name: str, render_index, width: int) -> None:
    doc_dir = Path(doc_dir)
    (doc_dir / index_name).write_text(render_index(iter_docs(doc_dir, width)), encoding="utf-8")


def validate_common(
    doc_dir,
    *,
    width: int,
    noun: str,
    field_labels: dict,
    list_fields,
    meta_start: str,
    meta_end: str,
    index_name: str,
    index_command: str,
    render_index,
    per_doc_extra=None,
) -> list[str]:
    """Return a list of human-readable validation errors (empty == valid).

    Generic checks shared by every numbered-doc tool. ``per_doc_extra`` is an optional
    callable applied to each successfully-parsed ``Doc``, returning extra error strings
    (used for schema-specific rules like ADR status or reference date ordering).
    """
    doc_dir = Path(doc_dir)
    errors: list[str] = []

    tags_path = doc_dir / "_tags.md"
    registry = load_tags(tags_path) if tags_path.exists() else {}
    if not tags_path.exists():
        errors.append("missing tag registry: _tags.md")
    elif list(registry) != sorted(registry):
        errors.append("_tags.md registry is not in alphabetical order")

    prefix_re = _id_prefix_re(width)
    placeholder = "N" * width
    for p in sorted(doc_dir.glob("*.md")):
        name = p.name
        if name == index_name or name.startswith("_"):
            continue
        if not prefix_re.match(name):
            errors.append(f"{name}: malformed {noun} filename (expected {placeholder}-title.md)")

    docs: list[Doc] = []
    for p in sorted(doc_dir.glob(_glob(width))):
        try:
            docs.append(parse_doc(p))
        except ValueError as exc:
            errors.append(f"{p.name}: {exc}")

    id_to_files: dict[str, list[str]] = {}
    for doc in docs:
        doc_id = str(doc.frontmatter.get("id", ""))
        id_to_files.setdefault(doc_id, []).append(doc.path.name)
    for doc_id, files in id_to_files.items():
        if len(files) > 1:
            errors.append(f"duplicate {noun} id '{doc_id}' found in: {', '.join(files)}")

    numeric_ids: list[int] = []
    for doc in docs:
        try:
            numeric_ids.append(int(doc.frontmatter.get("id")))
        except (TypeError, ValueError):
            pass  # non-numeric ids already flagged by schema checks
    if numeric_ids:
        max_id = max(numeric_ids)
        id_set = set(numeric_ids)
        first = f"{1:0{width}d}"
        for n in range(1, max_id + 1):
            if n not in id_set:
                errors.append(
                    f"missing {noun} id {n:0{width}d} (IDs must be contiguous starting at {first})"
                )

    for doc in docs:
        fm, name = doc.frontmatter, doc.path.name

        for key in field_labels:
            if key not in fm:
                errors.append(f"{name}: frontmatter missing field '{key}'")

        expected_prefix = f"{fm.get('id')}-"
        if not name.startswith(expected_prefix):
            errors.append(f"{name}: filename does not match id '{fm.get('id')}'")

        table = extract_meta_table(doc.body, meta_start, meta_end)
        if table is None:
            errors.append(f"{name}: missing meta table markers")
        elif table != render_meta_table(fm, field_labels, list_fields, meta_start, meta_end):
            errors.append(f"{name}: meta table drift (table != frontmatter)")

        doc_tags = fm.get("tags")
        if doc_tags is None:
            doc_tags = []
        elif not isinstance(doc_tags, list):
            errors.append(f"{name}: frontmatter field 'tags' must be a list")
            doc_tags = []
        for tag in doc_tags:
            if tag not in registry:
                errors.append(f"{name}: tag '{tag}' not in _tags.md registry")
        if list(doc_tags) != sorted(doc_tags):
            errors.append(f"{name}: tags are not in alphabetical order")

        if per_doc_extra is not None:
            errors.extend(per_doc_extra(doc))

    index_path = doc_dir / index_name
    expected_index = render_index(docs)
    if not index_path.exists():
        errors.append(f"missing {noun} index: {index_name}")
    elif index_path.read_text(encoding="utf-8") != expected_index:
        errors.append(f"{noun} index ({index_name}) is stale — run `{index_command}`")

    return errors
