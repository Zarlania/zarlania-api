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


def test_validate_reports_missing_field_without_crashing(reference_dt):
    (reference_dt.root / "000001-bad.md").write_text(
        '---\nid: "000001"\ntitle: Bad\n---\n\n# Bad\n', encoding="utf-8"
    )
    errors = validate(reference_dt)
    assert any("missing or null" in e for e in errors)


def test_validate_reports_null_scalar_field(reference_dt):
    (reference_dt.root / "000001-x.md").write_text(
        '---\nid: "000001"\ntitle: ~\ndescription: d\ntags: []\n'
        "created: 2026-07-23\nupdated: 2026-07-23\nrelated: []\n---\n\n"
        "# X\n<!-- reference-table:start -->\n<!-- reference-table:end -->\n",
        encoding="utf-8",
    )
    errors = validate(reference_dt)
    assert any("missing or null" in e and "title" in e for e in errors)


def test_null_tags_and_related_are_valid_empty(reference_dt):
    (reference_dt.root / "000001-x.md").write_text(
        '---\nid: "000001"\ntitle: X\ndescription: d\ntags:\n'
        "created: 2026-07-23\nupdated: 2026-07-23\nrelated:\n---\n\n"
        "# X\n<!-- reference-table:start -->\n<!-- reference-table:end -->\n",
        encoding="utf-8",
    )
    sync(reference_dt)
    assert validate(reference_dt) == []


def test_validate_reports_missing_table_markers(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    sync(reference_dt)
    doc = reference_dt.root / "000001-hello.md"
    text = (
        doc.read_text()
        .replace("<!-- reference-table:start -->", "")
        .replace("<!-- reference-table:end -->", "")
    )
    doc.write_text(text, encoding="utf-8")
    assert any("missing 'reference-table' markers" in e for e in validate(reference_dt))


def test_validate_flags_unexpected_non_numbered_file(reference_dt):
    _seed(reference_dt.root)
    (reference_dt.root / "overview.md").write_text("# Stray\n", encoding="utf-8")
    sync(reference_dt)
    assert any("unexpected file" in e for e in validate(reference_dt))
