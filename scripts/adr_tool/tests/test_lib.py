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


def _template(dir_: Path) -> None:
    """Write a minimal valid _template.md for tests that call new_adr."""
    content = (
        "---\n"
        'id: "NNNN"\n'
        "name: Template\n"
        "description: desc\n"
        "status: proposed\n"
        "date_proposed: YYYY-MM-DD\n"
        "date_accepted: null\n"
        "date_invalidated: null\n"
        "author: stimothy\n"
        "supersedes: []\n"
        "superseded_by: []\n"
        "tags: []\n"
        "---\n"
        "# ADR-NNNN: Template\n\n"
        f"{lib.META_START}\n"
        "| Field | Value |\n"
        "| --- | --- |\n"
        f"{lib.META_END}\n\n"
        "## Context and Problem Statement\n\n"
        "_What is the issue?_\n\n"
        "## Decision Outcome\n\n"
        "Chosen option: _option_.\n"
    )
    (dir_ / "_template.md").write_text(content, encoding="utf-8")


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
    _template(tmp_path)
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


def test_new_adr_uses_template_sections(tmp_path):
    """new_adr body sections come from _template.md, not a hardcoded constant."""
    _registry(tmp_path, "process")
    _template(tmp_path)
    path = lib.new_adr(
        tmp_path, name="Template Test", tags=["process"], author="stimothy", today="2026-06-15"
    )
    adr = lib.parse_adr(path)
    # The template helper above includes "## Context and Problem Statement"
    assert "## Context and Problem Statement" in adr.body


def test_new_adr_no_template_raises(tmp_path):
    """new_adr raises ValueError when _template.md is missing."""
    _registry(tmp_path, "process")
    with pytest.raises(ValueError, match="_template.md"):
        lib.new_adr(tmp_path, name="X", tags=["process"], author="stimothy")


def test_accept_adr_sets_status_and_date_and_syncs_table(tmp_path):
    _registry(tmp_path, "process")
    _template(tmp_path)
    path = lib.new_adr(
        tmp_path, name="My Choice", tags=["process"], author="stimothy", today="2026-06-15"
    )
    lib.accept_adr(path, today="2026-06-20")
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "accepted"
    assert adr.frontmatter["date_accepted"] == "2026-06-20"
    assert lib.extract_meta_table(adr.body) == lib.render_meta_table(adr.frontmatter)


def test_accept_adr_refuses_non_proposed(tmp_path):
    """accept_adr raises ValueError when ADR is not in 'proposed' status."""
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One", status="accepted")
    with pytest.raises(ValueError, match="cannot accept ADR with status 'accepted'"):
        lib.accept_adr(p)


def test_accept_adr_refuses_superseded(tmp_path):
    """accept_adr raises ValueError for any non-proposed status."""
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One", status="superseded")
    with pytest.raises(ValueError, match="only 'proposed' ADRs can be accepted"):
        lib.accept_adr(p)


def test_add_tag_appends_and_is_idempotent(tmp_path):
    _registry(tmp_path, "process")
    lib.add_tag(tmp_path / "_tags.md", "security", "security model")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"
    lib.add_tag(tmp_path / "_tags.md", "security", "ignored second desc")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"


def test_add_tag_rejects_pipe_in_tag(tmp_path):
    """add_tag raises ValueError when tag contains '|'."""
    _registry(tmp_path, "process")
    with pytest.raises(ValueError, match="tag must not contain"):
        lib.add_tag(tmp_path / "_tags.md", "bad|tag", "desc")


def test_add_tag_rejects_newline_in_tag(tmp_path):
    """add_tag raises ValueError when tag contains a newline."""
    _registry(tmp_path, "process")
    with pytest.raises(ValueError, match="tag must not contain"):
        lib.add_tag(tmp_path / "_tags.md", "bad\ntag", "desc")


def test_add_tag_rejects_pipe_in_description(tmp_path):
    """add_tag raises ValueError when description contains '|'."""
    _registry(tmp_path, "process")
    with pytest.raises(ValueError, match="description must not contain"):
        lib.add_tag(tmp_path / "_tags.md", "goodtag", "bad|desc")


def test_add_tag_rejects_newline_in_description(tmp_path):
    """add_tag raises ValueError when description contains a newline."""
    _registry(tmp_path, "process")
    with pytest.raises(ValueError, match="description must not contain"):
        lib.add_tag(tmp_path / "_tags.md", "goodtag", "bad\ndesc")


def test_write_index_makes_validation_pass(tmp_path):
    _registry(tmp_path, "process")
    _template(tmp_path)
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
    _template(tmp_path)
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


# --- New tests for validate_adrs robustness ---


def test_validate_unparseable_adr_returns_error_not_raise(tmp_path):
    """validate_adrs should catch parse errors and report them, not raise."""
    _registry(tmp_path, "process")
    bad = tmp_path / "0001-bad.md"
    bad.write_text("no frontmatter here\n", encoding="utf-8")
    (tmp_path / "README.md").write_text("", encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("0001-bad.md" in e for e in errors)


def test_validate_malformed_filename_reported(tmp_path):
    """validate_adrs detects filenames that don't match NNNN-title.md."""
    _registry(tmp_path, "process")
    # 3-digit prefix — not 4-digit
    bad = tmp_path / "001-short.md"
    bad.write_text("# just a file\n", encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index([]), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("001-short.md" in e and "malformed" in e for e in errors)


def test_validate_malformed_filename_no_digits(tmp_path):
    """validate_adrs detects filenames with no leading digit prefix."""
    _registry(tmp_path, "process")
    bad = tmp_path / "foo.md"
    bad.write_text("# plain\n", encoding="utf-8")
    (tmp_path / "README.md").write_text(lib.render_index([]), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("foo.md" in e and "malformed" in e for e in errors)


def test_validate_duplicate_ids_reported(tmp_path):
    """validate_adrs detects two ADR files with the same frontmatter id."""
    _registry(tmp_path, "process")
    # Create two files that both claim id 0001
    fm = {
        "id": "0001",
        "name": "Alpha",
        "description": "d",
        "status": "accepted",
        "date_proposed": "2026-06-15",
        "date_accepted": "2026-06-15",
        "date_invalidated": None,
        "author": "stimothy",
        "supersedes": [],
        "superseded_by": [],
        "tags": ["process"],
    }
    body = f"# ADR-0001: Alpha\n\n{lib.render_meta_table(fm)}\n\n## Context\nx\n"
    (tmp_path / "0001-alpha.md").write_text(
        f"---\n{lib.dump_frontmatter(fm)}\n---\n{body}", encoding="utf-8"
    )
    fm2 = dict(fm, name="Beta")
    body2 = f"# ADR-0001: Beta\n\n{lib.render_meta_table(fm2)}\n\n## Context\nx\n"
    (tmp_path / "0001-beta.md").write_text(
        f"---\n{lib.dump_frontmatter(fm2)}\n---\n{body2}", encoding="utf-8"
    )
    (tmp_path / "README.md").write_text(lib.render_index([]), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("duplicate" in e for e in errors)


def test_validate_gap_in_ids_reported(tmp_path):
    """validate_adrs reports missing IDs when sequence has gaps."""
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One", tags=("process",))
    _make_adr(tmp_path, "0003", "Three", tags=("process",))
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("0002" in e and "missing" in e for e in errors)


def test_validate_ids_starting_at_2_not_1_reported(tmp_path):
    """validate_adrs reports missing id 0001 when sequence starts at 0002."""
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0002", "Two", tags=("process",))
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("0001" in e and "missing" in e for e in errors)


def test_validate_empty_dir_no_gap_error(tmp_path):
    """validate_adrs with no ADR files should not report gap errors."""
    _registry(tmp_path, "process")
    (tmp_path / "README.md").write_text(lib.render_index([]), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    # Only possible error is missing _tags.md; no gap errors
    assert not any("missing ADR id" in e for e in errors)


def test_new_adr_sorts_tags_alphabetically(tmp_path):
    """new_adr stores tags in alphabetical order regardless of input order."""
    _registry(tmp_path, "alpha", "process", "zebra")
    _template(tmp_path)
    path = lib.new_adr(
        tmp_path,
        name="Sorted Tags",
        tags=["zebra", "process", "alpha"],
        author="stimothy",
        today="2026-06-15",
    )
    adr = lib.parse_adr(path)
    assert adr.frontmatter["tags"] == ["alpha", "process", "zebra"]


def test_add_tag_inserts_in_alphabetical_order(tmp_path):
    """add_tag keeps the registry sorted alphabetically, not append-only."""
    _registry(tmp_path, "configuration", "security")
    lib.add_tag(tmp_path / "_tags.md", "deployment", "deploy stuff")
    keys = list(lib.load_tags(tmp_path / "_tags.md"))
    assert keys == ["configuration", "deployment", "security"]


def test_validate_detects_unsorted_adr_tags(tmp_path):
    _registry(tmp_path, "process", "security")
    _make_adr(tmp_path, "0001", "One", tags=("security", "process"))
    (tmp_path / "README.md").write_text(lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("alphabetical order" in e for e in errors)


def test_validate_detects_unsorted_registry(tmp_path):
    # Registry rows out of alphabetical order
    (tmp_path / "_tags.md").write_text(
        "# ADR Tags\n\n| Tag | Description |\n| --- | --- |\n| security | s |\n| process | p |\n",
        encoding="utf-8",
    )
    (tmp_path / "README.md").write_text(lib.render_index([]), encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("_tags.md registry is not in alphabetical order" in e for e in errors)


def test_render_index_missing_id_uses_question_mark():
    """render_index uses '?' for missing frontmatter id rather than raising KeyError."""
    from pathlib import Path

    adr = lib.Adr(
        path=Path("0001-x.md"),
        frontmatter={"name": "X", "status": "proposed", "tags": []},
        body="",
    )
    index = lib.render_index([adr])
    assert "[?](0001-x.md)" in index
