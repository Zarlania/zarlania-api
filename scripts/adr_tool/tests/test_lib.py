import textwrap
from pathlib import Path

import lib
import pytest


def test_slugify_basic():
    assert lib.slugify("Use Postgres for Persistence!") == "use-postgres-for-persistence"


def test_slugify_collapses_separators():
    assert lib.slugify("  A / B  --  C ") == "a-b-c"


def test_field_labels_cover_schema():
    assert lib.FIELD_LABELS["id"] == "ID"
    assert "tags" in lib.FIELD_LABELS
    assert len(lib.FIELD_LABELS) == 11


def _write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "0001-x.md"
    p.write_text(textwrap.dedent(text), encoding="utf-8")
    return p


def test_parse_adr_splits_frontmatter_and_body(tmp_path):
    p = _write(
        tmp_path,
        """\
        ---
        id: "0001"
        name: Example
        tags: [a, b]
        ---
        # Body here
        text
        """,
    )
    adr = lib.parse_adr(p)
    assert adr.frontmatter["id"] == "0001"
    assert adr.frontmatter["tags"] == ["a", "b"]
    assert adr.body.startswith("# Body here")


def test_parse_adr_without_frontmatter_raises(tmp_path):
    p = tmp_path / "0002-y.md"
    p.write_text("no frontmatter\n", encoding="utf-8")
    with pytest.raises(ValueError):
        lib.parse_adr(p)


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
        "id": "0001",
        "name": "Example",
        "description": "d",
        "status": "proposed",
        "date_proposed": "2026-06-15",
        "date_accepted": None,
        "date_invalidated": None,
        "author": "stimothy",
        "supersedes": [],
        "superseded_by": [],
        "tags": ["process"],
    }
    table = lib.render_meta_table(fm)
    assert table.startswith(lib.META_START)
    assert table.rstrip().endswith(lib.META_END)
    body = f"intro\n\n{table}\n\n## Section\n"
    assert lib.extract_meta_table(body) == table


def test_extract_meta_table_missing_returns_none():
    assert lib.extract_meta_table("no markers here") is None


def _make_adr(dir_: Path, num: str, name: str, status="accepted", tags=("process",)):
    fm = {
        "id": num,
        "name": name,
        "description": "d",
        "status": status,
        "date_proposed": "2026-06-15",
        "date_accepted": "2026-06-15",
        "date_invalidated": None,
        "author": "stimothy",
        "supersedes": [],
        "superseded_by": [],
        "tags": list(tags),
    }
    body = f"# ADR-{num}: {name}\n\n{lib.render_meta_table(fm)}\n\n## Context\nx\n"
    p = dir_ / f"{num}-{lib.slugify(name)}.md"
    p.write_text(f"---\n{lib.dump_frontmatter(fm)}\n---\n{body}", encoding="utf-8")
    return p


def test_next_id_empty_dir(tmp_path):
    assert lib.next_id(tmp_path) == "0001"


def test_next_id_increments(tmp_path):
    _make_adr(tmp_path, "0001", "One")
    _make_adr(tmp_path, "0004", "Four")
    assert lib.next_id(tmp_path) == "0005"


def test_iter_adrs_sorted(tmp_path):
    _make_adr(tmp_path, "0002", "Two")
    _make_adr(tmp_path, "0001", "One")
    ids = [a.frontmatter["id"] for a in lib.iter_adrs(tmp_path)]
    assert ids == ["0001", "0002"]


def test_load_tags_parses_registry(tmp_path):
    (tmp_path / "_tags.md").write_text(
        "# ADR Tags\n\n| Tag | Description |\n| --- | --- |\n"
        "| process | how we work |\n| security | security model |\n",
        encoding="utf-8",
    )
    tags = lib.load_tags(tmp_path / "_tags.md")
    assert tags == {"process": "how we work", "security": "security model"}


def test_render_index_lists_adrs(tmp_path):
    _make_adr(tmp_path, "0001", "One", tags=("process",))
    index = lib.render_index(lib.iter_adrs(tmp_path))
    assert "| ID | Name | Status | Tags |" in index
    assert "[0001](0001-one.md)" in index


def _registry(tmp_path: Path, *tags):
    rows = "".join(f"| {t} | desc |\n" for t in tags)
    (tmp_path / "_tags.md").write_text(
        "# ADR Tags\n\n| Tag | Description |\n| --- | --- |\n" + rows, encoding="utf-8"
    )


def test_validate_clean_repo_has_no_errors(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One", tags=("process",))
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    assert lib.validate_adrs(tmp_path) == []


def test_validate_detects_table_drift(tmp_path):
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8").replace("| Status | accepted |", "| Status | proposed |")
    p.write_text(text, encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("drift" in e.lower() for e in errors)


def test_validate_detects_unknown_tag(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One", tags=("ghost",))
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("ghost" in e for e in errors)


def test_validate_detects_bad_status_and_id_mismatch(tmp_path):
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8").replace("id: '0001'", 'id: "0009"')
    p.write_text(text, encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("id" in e.lower() for e in errors)


def test_validate_detects_stale_index(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One")
    (tmp_path / "README.md").write_text("# stale\n", encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("index" in e.lower() for e in errors)


def test_new_adr_creates_valid_proposed_file(tmp_path):
    _registry(tmp_path, "process")
    path = lib.new_adr(
        tmp_path, name="My Choice", tags=["process"], author="stimothy", today="2026-06-15"
    )
    assert path.name == "0001-my-choice.md"
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "proposed"
    assert adr.frontmatter["date_proposed"] == "2026-06-15"
    assert adr.frontmatter["id"] == "0001"
    # table is in sync from creation
    assert lib.extract_meta_table(adr.body) == lib.render_meta_table(adr.frontmatter)


def test_accept_adr_sets_status_and_date_and_syncs_table(tmp_path):
    _registry(tmp_path, "process")
    path = lib.new_adr(
        tmp_path, name="My Choice", tags=["process"], author="stimothy", today="2026-06-15"
    )
    lib.accept_adr(path, today="2026-06-20")
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "accepted"
    assert adr.frontmatter["date_accepted"] == "2026-06-20"
    assert lib.extract_meta_table(adr.body) == lib.render_meta_table(adr.frontmatter)


def test_add_tag_appends_and_is_idempotent(tmp_path):
    _registry(tmp_path, "process")
    lib.add_tag(tmp_path / "_tags.md", "security", "security model")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"
    lib.add_tag(tmp_path / "_tags.md", "security", "ignored second desc")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"


def test_write_index_makes_validation_pass(tmp_path):
    _registry(tmp_path, "process")
    lib.new_adr(tmp_path, name="One", tags=["process"], author="stimothy", today="2026-06-15")
    lib.write_index(tmp_path)
    assert lib.validate_adrs(tmp_path) == []


def test_today_iso_returns_iso_string():
    result = lib._today_iso()
    # Must match YYYY-MM-DD
    import re

    assert re.match(r"^\d{4}-\d{2}-\d{2}$", result)


def test_display_value_list_field_non_list_scalar():
    # LIST_FIELDS with a non-list scalar hits the `return str(value)` branch (line 74)
    assert lib.display_value("tags", "solo-tag") == "solo-tag"


def test_validate_missing_tags_registry(tmp_path):
    # No _tags.md at all — should report missing registry error
    _make_adr(tmp_path, "0001", "One")
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("_tags.md" in e for e in errors)


def test_validate_missing_frontmatter_field(tmp_path):
    # Write an ADR with a frontmatter field removed so the 'missing field' error fires
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8")
    # Remove the 'author' line from frontmatter
    stripped = "\n".join(line for line in text.splitlines() if not line.startswith("author:"))
    p.write_text(stripped, encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("missing field" in e for e in errors)


def test_validate_invalid_status(tmp_path):
    # ADR with an invalid status value triggers the status-check error
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8").replace("status: accepted", "status: unknown-status")
    p.write_text(text, encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("invalid status" in e for e in errors)


def test_validate_missing_meta_table_markers(tmp_path):
    # ADR body without meta-table markers triggers 'missing meta table markers' error
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8")
    # Strip out the meta table block
    import re

    cleaned = re.sub(
        r"<!-- adr-meta:start -->.*?<!-- adr-meta:end -->",
        "",
        text,
        flags=re.DOTALL,
    )
    p.write_text(cleaned, encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("missing meta table markers" in e for e in errors)


def test_accept_adr_no_meta_table_prepends(tmp_path):
    # When body has no meta-table markers, _rewrite_with_synced_table prepends the table
    _registry(tmp_path, "process")
    path = lib.new_adr(
        tmp_path, name="No Table", tags=["process"], author="stimothy", today="2026-06-15"
    )
    # Remove the meta table from the file body so the prepend fallback is hit
    import re

    text = path.read_text(encoding="utf-8")
    cleaned = re.sub(
        r"<!-- adr-meta:start -->.*?<!-- adr-meta:end -->\n?",
        "",
        text,
        flags=re.DOTALL,
    )
    path.write_text(cleaned, encoding="utf-8")
    lib.accept_adr(path, today="2026-06-20")
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "accepted"
    assert lib.META_START in adr.body
