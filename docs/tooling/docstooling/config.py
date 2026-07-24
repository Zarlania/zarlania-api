"""Doc-type configuration.

A DocType parameterizes the generic library for one kind of document. The
reference doc-type is defined here; a future ADR doc-type will add its own
factory and reuse the same library.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DocType:
    name: str
    root: Path
    id_width: int
    table_marker: str
    index_marker: str
    template_path: Path
    tags_path: Path
    readme_path: Path


def reference_doctype(root: Path) -> DocType:
    """Build the reference DocType rooted at ``root`` (e.g. ``docs/references``)."""
    return DocType(
        name="reference",
        root=root,
        id_width=6,
        table_marker="reference-table",
        index_marker="reference-index",
        template_path=root / "_template.md",
        tags_path=root / "_tags.md",
        readme_path=root / "README.md",
    )


# docs/tooling/docstooling/config.py -> parents[2] is docs/, so docs/references.
_DEFAULT_ROOT = Path(__file__).resolve().parents[2] / "references"
REFERENCE = reference_doctype(_DEFAULT_ROOT)
