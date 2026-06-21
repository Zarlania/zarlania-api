import textwrap
from pathlib import Path

import core
import pytest

# Minimal schema used by these tests (mirrors how a tool configures core).
LABELS = {"id": "ID", "title": "Title", "tags": "Tags"}
LIST_FIELDS = {"tags"}
START = "<!-- t-meta:start -->"
END = "<!-- t-meta:end -->"


def test_slugify_collapses_and_strips():
    assert core.slugify("  A / B –– C! ") == "a-b-c"


def test_today_iso_format():
    import re

    assert re.match(r"^\d{4}-\d{2}-\d{2}$", core.today_iso())


def test_parse_doc_splits_frontmatter_and_body(tmp_path):
    p = tmp_path / "0001-x.md"
    p.write_text(
        textwrap.dedent(
            """\
            ---
            id: "0001"
            tags: [a, b]
            ---
            # Body
            text
            """
        ),
        encoding="utf-8",
    )
    doc = core.parse_doc(p)
    assert doc.frontmatter["id"] == "0001"
    assert doc.frontmatter["tags"] == ["a", "b"]
    assert doc.body.startswith("# Body")


def test_parse_doc_without_frontmatter_raises(tmp_path):
    p = tmp_path / "0001-x.md"
    p.write_text("no frontmatter\n", encoding="utf-8")
    with pytest.raises(ValueError):
        core.parse_doc(p)


def test_parse_doc_non_mapping_frontmatter_raises(tmp_path):
    # Frontmatter that parses as a YAML list (not a mapping) must raise a
    # ValueError, not crash downstream `.get()` callers with an AttributeError.
    p = tmp_path / "0001-x.md"
    p.write_text("---\n- not-a-mapping\n---\nbody\n", encoding="utf-8")
    with pytest.raises(ValueError, match="frontmatter must be a mapping"):
        core.parse_doc(p)


def test_parse_doc_invalid_yaml_raises_valueerror(tmp_path):
    # Malformed YAML between the delimiters must surface as a ValueError so the
    # `check` command reports it instead of crashing on yaml.YAMLError.
    p = tmp_path / "0001-x.md"
    p.write_text('---\nid: "0001\n  : :\n---\nbody\n', encoding="utf-8")
    with pytest.raises(ValueError, match="invalid YAML frontmatter"):
        core.parse_doc(p)


def test_dump_frontmatter_uses_given_order():
    out = core.dump_frontmatter({"tags": ["x"], "id": "7", "title": "T"}, LABELS)
    lines = out.splitlines()
    assert lines[0].startswith("id:")
    assert lines[1].startswith("title:")


def test_display_value_branches():
    assert core.display_value(None, False) == "—"
    assert core.display_value([], True) == "—"
    assert core.display_value(["a", "b"], True) == "a, b"
    assert core.display_value("solo", True) == "solo"
    assert core.display_value("accepted", False) == "accepted"


def test_meta_table_roundtrips_via_extract():
    fm = {"id": "0001", "title": "Example", "tags": ["x"]}
    table = core.render_meta_table(fm, LABELS, LIST_FIELDS, START, END)
    assert table.startswith(START)
    assert table.rstrip().endswith(END)
    body = f"intro\n\n{table}\n\n## Section\n"
    assert core.extract_meta_table(body, START, END) == table


def test_extract_meta_table_missing_returns_none():
    assert core.extract_meta_table("nothing here", START, END) is None


def test_next_id_empty_and_increment_width6(tmp_path):
    assert core.next_id(tmp_path, 6) == "000001"
    (tmp_path / "000001-a.md").write_text("x", encoding="utf-8")
    (tmp_path / "000004-b.md").write_text("x", encoding="utf-8")
    assert core.next_id(tmp_path, 6) == "000005"


def test_next_id_ignores_other_widths(tmp_path):
    # A 4-digit file must not be counted when width is 6.
    (tmp_path / "0001-a.md").write_text("x", encoding="utf-8")
    assert core.next_id(tmp_path, 6) == "000001"


def test_iter_docs_sorted(tmp_path):
    for n in ("000002", "000001"):
        (tmp_path / f"{n}-x.md").write_text(f'---\nid: "{n}"\n---\nbody\n', encoding="utf-8")
    ids = [d.frontmatter["id"] for d in core.iter_docs(tmp_path, 6)]
    assert ids == ["000001", "000002"]


def _registry(tmp_path: Path, *tags):
    rows = "".join(f"| {t} | desc |\n" for t in tags)
    (tmp_path / "_tags.md").write_text(
        "# Tags\n\n| Tag | Description |\n| --- | --- |\n" + rows, encoding="utf-8"
    )


def test_load_tags_parses_registry(tmp_path):
    _registry(tmp_path, "alpha", "beta")
    assert core.load_tags(tmp_path / "_tags.md") == {"alpha": "desc", "beta": "desc"}


def test_add_tag_inserts_sorted_and_idempotent(tmp_path):
    _registry(tmp_path, "alpha", "gamma")
    core.add_tag(tmp_path / "_tags.md", "beta", "b")
    assert list(core.load_tags(tmp_path / "_tags.md")) == ["alpha", "beta", "gamma"]
    core.add_tag(tmp_path / "_tags.md", "beta", "ignored")
    assert core.load_tags(tmp_path / "_tags.md")["beta"] == "b"


def test_add_tag_rejects_pipe_and_newline(tmp_path):
    _registry(tmp_path, "alpha")
    with pytest.raises(ValueError, match="tag must not contain"):
        core.add_tag(tmp_path / "_tags.md", "bad|tag", "d")
    with pytest.raises(ValueError, match="description must not contain"):
        core.add_tag(tmp_path / "_tags.md", "good", "bad\ndesc")


def test_add_tag_missing_separator_raises(tmp_path):
    (tmp_path / "_tags.md").write_text("# Tags\n\nno table here\n", encoding="utf-8")
    with pytest.raises(ValueError, match="header separator"):
        core.add_tag(tmp_path / "_tags.md", "alpha", "d")


# --- validate_common, exercised through a minimal schema ---


def _render_index(docs):
    lines = ["# Idx", "", "| ID | Title |", "| --- | --- |"]
    for d in docs:
        fm = d.frontmatter
        lines.append(f"| [{fm.get('id', '?')}]({d.path.name}) | {fm.get('title', '')} |")
    return "\n".join(lines) + "\n"


def _make_doc(tmp_path: Path, num: str, title: str, tags=("alpha",)):
    fm = {"id": num, "title": title, "tags": list(tags)}
    body = (
        f"# {title}\n\n{core.render_meta_table(fm, LABELS, LIST_FIELDS, START, END)}\n\n## X\nx\n"
    )
    p = tmp_path / f"{num}-{core.slugify(title)}.md"
    p.write_text(f"---\n{core.dump_frontmatter(fm, LABELS)}\n---\n{body}", encoding="utf-8")
    return p


def _validate(tmp_path, per_doc_extra=None):
    return core.validate_common(
        tmp_path,
        width=6,
        noun="reference doc",
        field_labels=LABELS,
        list_fields=LIST_FIELDS,
        meta_start=START,
        meta_end=END,
        index_name="README.md",
        index_command="./scripts/ref index",
        render_index=_render_index,
        per_doc_extra=per_doc_extra,
    )


def test_validate_clean(tmp_path):
    _registry(tmp_path, "alpha")
    _make_doc(tmp_path, "000001", "One")
    core.write_index(tmp_path, "README.md", _render_index, 6)
    assert _validate(tmp_path) == []


def test_validate_reports_missing_registry_and_stale_index(tmp_path):
    _make_doc(tmp_path, "000001", "One")
    (tmp_path / "README.md").write_text("# stale\n", encoding="utf-8")
    errors = _validate(tmp_path)
    assert any("_tags.md" in e for e in errors)
    assert any("index" in e.lower() and "stale" in e for e in errors)


def test_validate_detects_unknown_tag_and_gap(tmp_path):
    _registry(tmp_path, "alpha")
    _make_doc(tmp_path, "000001", "One", tags=("ghost",))
    _make_doc(tmp_path, "000003", "Three")
    core.write_index(tmp_path, "README.md", _render_index, 6)
    errors = _validate(tmp_path)
    assert any("ghost" in e for e in errors)
    assert any("000002" in e and "missing" in e for e in errors)


def test_validate_detects_table_drift_and_malformed_filename(tmp_path):
    _registry(tmp_path, "alpha")
    p = _make_doc(tmp_path, "000001", "One")
    p.write_text(
        p.read_text(encoding="utf-8").replace("| Title | One |", "| Title | Two |"),
        encoding="utf-8",
    )
    (tmp_path / "00x-bad.md").write_text("# bad\n", encoding="utf-8")
    core.write_index(tmp_path, "README.md", _render_index, 6)
    errors = _validate(tmp_path)
    assert any("drift" in e for e in errors)
    assert any("malformed" in e and "00x-bad.md" in e for e in errors)


def test_validate_per_doc_extra_runs(tmp_path):
    _registry(tmp_path, "alpha")
    _make_doc(tmp_path, "000001", "One")
    core.write_index(tmp_path, "README.md", _render_index, 6)
    errors = _validate(tmp_path, per_doc_extra=lambda d: ["custom failure"])
    assert "custom failure" in errors


def test_validate_reports_non_list_tags_instead_of_crashing(tmp_path):
    # A scalar `tags` value must be reported as an error, not iterated/sorted
    # (which would crash with a TypeError or process a string character-by-character).
    _registry(tmp_path, "alpha")
    p = _make_doc(tmp_path, "000001", "One")
    doc = core.parse_doc(p)
    doc.frontmatter["tags"] = 1
    body = (
        f"# {doc.frontmatter['title']}\n\n"
        f"{core.render_meta_table(doc.frontmatter, LABELS, LIST_FIELDS, START, END)}\n\n## X\nx\n"
    )
    p.write_text(
        f"---\n{core.dump_frontmatter(doc.frontmatter, LABELS)}\n---\n{body}", encoding="utf-8"
    )
    core.write_index(tmp_path, "README.md", _render_index, 6)
    errors = _validate(tmp_path)
    assert any("field 'tags' must be a list" in e for e in errors)
