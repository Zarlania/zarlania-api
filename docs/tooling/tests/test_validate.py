from docstooling.sync import sync  # Task 10; import here so validate tests use synced fixtures
from docstooling.validate import validate
from tests.conftest import write_doc


def _seed(root):
    write_doc(root, "000001", "hello", title="Hello", tags=["http"], related=["000002"])
    write_doc(root, "000002", "world", title="World", tags=["controllers"], related=[])


def test_validate_passes_after_sync(reference_dt):
    _seed(reference_dt.root)
    sync(reference_dt)
    assert validate(reference_dt) == []


def test_validate_flags_unknown_tag(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["nope"], related=[])
    sync(reference_dt)
    assert any("unknown tag 'nope'" in e for e in validate(reference_dt))


def test_validate_flags_missing_related(reference_dt):
    write_doc(
        reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=["000099"]
    )
    sync(reference_dt)
    assert any("related id '000099'" in e for e in validate(reference_dt))


def test_validate_flags_drifted_table(reference_dt):
    _seed(reference_dt.root)
    sync(reference_dt)
    doc = reference_dt.root / "000001-hello.md"
    doc.write_text(doc.read_text().replace("| Title | Hello |", "| Title | Tampered |"))
    assert any("table out of sync" in e for e in validate(reference_dt))


def test_validate_flags_filename_id_mismatch(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    (reference_dt.root / "000001-hello.md").rename(reference_dt.root / "000002-hello.md")
    sync(reference_dt)
    # after rename the file's frontmatter id (000001) disagrees with filename (000002-)
    assert any("filename must start" in e for e in validate(reference_dt))
