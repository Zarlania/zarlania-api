import pytest

from docstooling.document import load_all, load_document
from docstooling.frontmatter import FrontmatterError
from tests.conftest import write_doc


def test_load_document_reads_all_fields(reference_root):
    path = write_doc(
        reference_root, "000001", "hello", title="Hello", tags=["http"], related=["000002"]
    )
    doc = load_document(path)
    assert doc.id == "000001"
    assert doc.title == "Hello"
    assert doc.tags == ["http"]
    assert doc.related == ["000002"]
    assert "Body of Hello." in doc.body


def test_load_document_rejects_missing_field(reference_root):
    path = reference_root / "000001-bad.md"
    path.write_text('---\nid: "000001"\ntitle: X\n---\n\n# X\n', encoding="utf-8")
    with pytest.raises(FrontmatterError):
        load_document(path)


def test_load_all_sorted_by_id_and_skips_meta_files(reference_root):
    write_doc(reference_root, "000002", "b", title="B", tags=[], related=[])
    write_doc(reference_root, "000001", "a", title="A", tags=[], related=[])
    docs = load_all(reference_root)
    assert [d.id for d in docs] == ["000001", "000002"]  # _tags/_template/README skipped
