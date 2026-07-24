from docstooling.document import load_all
from docstooling.markers import extract_region
from docstooling.sync import sync
from tests.conftest import write_doc


def test_sync_fills_table_and_index(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    sync(reference_dt)
    docs = load_all(reference_dt.root)
    body = docs[0].path.read_text()
    assert "| Title | Hello |" in extract_region(body, "reference-table")
    readme = reference_dt.readme_path.read_text()
    assert "[000001](000001-hello.md)" in extract_region(readme, "reference-index")


def test_sync_is_idempotent(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    sync(reference_dt)
    first = reference_dt.root.joinpath("000001-hello.md").read_text()
    sync(reference_dt)
    assert reference_dt.root.joinpath("000001-hello.md").read_text() == first
