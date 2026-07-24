"""Aggregate structural validation for a doc-type."""

from __future__ import annotations

import re

from .config import DocType
from .document import Document, load_document
from .frontmatter import FrontmatterError
from .index import render_index
from .markers import MarkerError, extract_region
from .sequence import validate_sequence
from .table import render_table
from .tags import load_tags_ordered

_SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
_H1 = re.compile(r"^# (.+)$", re.MULTILINE)


def validate(dt: DocType) -> list[str]:
    errors: list[str] = []
    docs: list[Document] = []
    for path in sorted(dt.root.glob("[0-9]*.md")):
        try:
            docs.append(load_document(path))
        except FrontmatterError as exc:
            errors.append(str(exc))

    # Any other Markdown file is unexpected: reference docs must be numbered, and
    # only README.md (the generated index) and the _*.md support files are allowed.
    for path in sorted(dt.root.glob("*.md")):
        name = path.name
        if name[0].isdigit() or name == dt.readme_path.name or name.startswith("_"):
            continue
        errors.append(f"{name}: unexpected file (reference docs must be NNNNNN-<slug>.md)")

    by_id = {d.id: d for d in docs}
    errors.extend(validate_sequence(docs, dt.id_width))

    for doc in docs:
        name = doc.path.name
        if not name.startswith(f"{doc.id}-"):
            errors.append(f"{name}: filename must start with id '{doc.id}-'")
        elif not _SLUG.fullmatch(name[len(doc.id) + 1 : -len(".md")]):
            errors.append(f"{name}: filename slug must be kebab-case ([a-z0-9] and '-')")

    ordered_tags = load_tags_ordered(dt.tags_path)
    if ordered_tags != sorted(ordered_tags):
        errors.append(f"{dt.tags_path.name}: tags must be in alphabetical order")
    known = set(ordered_tags)
    for doc in docs:
        if doc.tags != sorted(doc.tags):
            errors.append(f"{doc.path.name}: tags must be in alphabetical order")
        for tag in doc.tags:
            if tag not in known:
                errors.append(f"{doc.path.name}: unknown tag '{tag}' (not in {dt.tags_path.name})")
        for rid in doc.related:
            if rid not in by_id:
                errors.append(f"{doc.path.name}: related id '{rid}' does not exist")

    for doc in docs:
        heading = _H1.search(doc.body)
        if heading is None:
            errors.append(f"{doc.path.name}: body must have an H1 heading")
        elif heading.group(1).strip() != doc.title:
            errors.append(
                f"{doc.path.name}: H1 '{heading.group(1).strip()}' "
                f"must match frontmatter title '{doc.title}'"
            )

    for doc in docs:
        try:
            current = extract_region(doc.path.read_text(encoding="utf-8"), dt.table_marker)
        except MarkerError:
            errors.append(f"{doc.path.name}: missing '{dt.table_marker}' markers")
            continue
        if current != render_table(doc, by_id):
            errors.append(f"{doc.path.name}: table out of sync (run sync)")

    try:
        index_now = extract_region(dt.readme_path.read_text(encoding="utf-8"), dt.index_marker)
        if index_now != render_index(docs):
            errors.append(f"{dt.readme_path.name}: index out of sync (run sync)")
    except MarkerError:
        errors.append(f"{dt.readme_path.name}: missing '{dt.index_marker}' markers")

    return errors
