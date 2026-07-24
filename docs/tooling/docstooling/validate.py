"""Aggregate structural validation for a doc-type."""

from __future__ import annotations

from .config import DocType
from .document import Document, load_document
from .frontmatter import FrontmatterError
from .index import render_index
from .markers import MarkerError, extract_region
from .sequence import validate_sequence
from .table import render_table
from .tags import load_tags


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
        if not doc.path.name.startswith(f"{doc.id}-"):
            errors.append(f"{doc.path.name}: filename must start with id '{doc.id}-'")

    known = load_tags(dt.tags_path)
    for doc in docs:
        for tag in doc.tags:
            if tag not in known:
                errors.append(f"{doc.path.name}: unknown tag '{tag}' (not in {dt.tags_path.name})")
        for rid in doc.related:
            if rid not in by_id:
                errors.append(f"{doc.path.name}: related id '{rid}' does not exist")

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
