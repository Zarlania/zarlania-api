import lib


def test_slugify_basic():
    assert lib.slugify("Use Postgres for Persistence!") == "use-postgres-for-persistence"


def test_slugify_collapses_separators():
    assert lib.slugify("  A / B  --  C ") == "a-b-c"


def test_field_labels_cover_schema():
    assert lib.FIELD_LABELS["id"] == "ID"
    assert "tags" in lib.FIELD_LABELS
    assert len(lib.FIELD_LABELS) == 11


import textwrap
from pathlib import Path


def _write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "0001-x.md"
    p.write_text(textwrap.dedent(text), encoding="utf-8")
    return p


def test_parse_adr_splits_frontmatter_and_body(tmp_path):
    p = _write(tmp_path, """\
        ---
        id: "0001"
        name: Example
        tags: [a, b]
        ---
        # Body here
        text
        """)
    adr = lib.parse_adr(p)
    assert adr.frontmatter["id"] == "0001"
    assert adr.frontmatter["tags"] == ["a", "b"]
    assert adr.body.startswith("# Body here")


def test_parse_adr_without_frontmatter_raises(tmp_path):
    p = tmp_path / "0002-y.md"
    p.write_text("no frontmatter\n", encoding="utf-8")
    try:
        lib.parse_adr(p)
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_dump_frontmatter_orders_fields():
    fm = {"name": "N", "id": "0007", "tags": ["x"]}
    out = lib.dump_frontmatter(fm)
    lines = out.splitlines()
    assert lines[0].startswith("id:")
    assert lines[1].startswith("name:")
    assert "tags:" in out


def test_display_value_handles_empty_and_lists():
    assert lib.display_value("author", None) == "—"
    assert lib.display_value("tags", []) == "—"
    assert lib.display_value("tags", ["a", "b"]) == "a, b"
    assert lib.display_value("status", "accepted") == "accepted"


def test_render_meta_table_roundtrips_via_extract():
    fm = {
        "id": "0001", "name": "Example", "description": "d", "status": "proposed",
        "date_proposed": "2026-06-15", "date_accepted": None, "date_invalidated": None,
        "author": "stimothy", "supersedes": [], "superseded_by": [], "tags": ["process"],
    }
    table = lib.render_meta_table(fm)
    assert table.startswith(lib.META_START)
    assert table.rstrip().endswith(lib.META_END)
    body = f"intro\n\n{table}\n\n## Section\n"
    assert lib.extract_meta_table(body) == table


def test_extract_meta_table_missing_returns_none():
    assert lib.extract_meta_table("no markers here") is None
