from pathlib import Path

from docstooling.config import REFERENCE, reference_doctype


def test_reference_doctype_derives_paths_from_root():
    dt = reference_doctype(Path("/x/references"))
    assert dt.id_width == 6
    assert dt.table_marker == "reference-table"
    assert dt.index_marker == "reference-index"
    assert dt.tags_path == Path("/x/references/_tags.md")
    assert dt.readme_path == Path("/x/references/README.md")
    assert dt.template_path == Path("/x/references/_template.md")


def test_default_reference_root_points_at_docs_references():
    assert REFERENCE.root.name == "references"
    assert REFERENCE.root.parent.name == "docs"
