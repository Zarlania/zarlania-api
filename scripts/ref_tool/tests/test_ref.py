from pathlib import Path

import pytest
import ref


def _registry(d: Path, *tags):
    rows = "".join(f"| {t} | desc |\n" for t in tags)
    (d / "_tags.md").write_text(
        "# Reference Doc Tags\n\n| Tag | Description |\n| --- | --- |\n" + rows,
        encoding="utf-8",
    )


def _template(d: Path) -> None:
    content = (
        "---\n"
        'id: "NNNNNN"\n'
        "title: Title\n"
        "description: desc\n"
        "tags: []\n"
        "created: YYYY-MM-DD\n"
        "updated: YYYY-MM-DD\n"
        "related: []\n"
        "---\n"
        "# Title\n\n"
        f"{ref.META_START}\n"
        "| Field | Value |\n"
        "| --- | --- |\n"
        f"{ref.META_END}\n\n"
        "## Overview\n\nWhat this explains.\n\n"
        "## Scope\n\nWhat it covers.\n\n"
        "## Rules / constraints\n\n- rule\n\n"
        "## Related\n\n- links\n"
    )
    (d / "_template.md").write_text(content, encoding="utf-8")


def test_field_labels_cover_reference_schema():
    assert list(ref.FIELD_LABELS) == [
        "id",
        "title",
        "description",
        "tags",
        "created",
        "updated",
        "related",
    ]
    assert ref.WIDTH == 6


def test_new_doc_creates_valid_doc(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    path = ref.new_doc(tmp_path, title="My Guide", tags=["architecture"], today="2026-06-21")
    assert path.name == "000001-my-guide.md"
    doc = ref.parse_doc(path)
    assert doc.frontmatter["id"] == "000001"
    assert doc.frontmatter["created"] == "2026-06-21"
    assert doc.frontmatter["updated"] == "2026-06-21"
    assert doc.frontmatter["related"] == []
    assert ref.extract_meta_table(doc.body) == ref.render_meta_table(doc.frontmatter)
    assert "## Overview" in doc.body


def test_new_doc_sorts_tags(tmp_path):
    _registry(tmp_path, "architecture", "domain-model", "zebra")
    _template(tmp_path)
    path = ref.new_doc(tmp_path, title="Sorted", tags=["zebra", "architecture", "domain-model"])
    assert ref.parse_doc(path).frontmatter["tags"] == [
        "architecture",
        "domain-model",
        "zebra",
    ]


def test_new_doc_missing_template_raises(tmp_path):
    _registry(tmp_path, "architecture")
    with pytest.raises(ValueError, match="_template.md"):
        ref.new_doc(tmp_path, title="X", tags=["architecture"])


def test_write_index_makes_validation_pass(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    ref.write_index(tmp_path)
    assert ref.validate_docs(tmp_path) == []


def test_render_index_lists_docs(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    index = ref.render_index(ref.iter_docs(tmp_path))
    assert "| ID | Title | Tags |" in index
    assert "[000001](000001-one.md)" in index


def test_validate_detects_updated_before_created(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    path = ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    doc = ref.parse_doc(path)
    doc.frontmatter["updated"] = "2026-06-20"  # before created
    path.write_text(
        f"---\n{ref.dump_frontmatter(doc.frontmatter)}\n---\n"
        f"# {doc.frontmatter['title']}\n\n"
        f"{ref.render_meta_table(doc.frontmatter)}\n\n## Overview\nx\n",
        encoding="utf-8",
    )
    ref.write_index(tmp_path)
    errors = ref.validate_docs(tmp_path)
    assert any("updated" in e and "before" in e for e in errors)


def test_validate_detects_non_iso_date(tmp_path):
    # A non-YYYY-MM-DD date must be reported as a format error rather than
    # silently passing a lexicographic comparison.
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    path = ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    doc = ref.parse_doc(path)
    doc.frontmatter["updated"] = "2026-13-99"  # not a real calendar date
    path.write_text(
        f"---\n{ref.dump_frontmatter(doc.frontmatter)}\n---\n"
        f"# {doc.frontmatter['title']}\n\n"
        f"{ref.render_meta_table(doc.frontmatter)}\n\n## Overview\nx\n",
        encoding="utf-8",
    )
    ref.write_index(tmp_path)
    errors = ref.validate_docs(tmp_path)
    assert any("YYYY-MM-DD" in e for e in errors)


def test_validate_detects_unknown_tag(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    # bypass any CLI tag guard: write a doc with an unregistered tag directly
    path = ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    doc = ref.parse_doc(path)
    doc.frontmatter["tags"] = ["ghost"]
    path.write_text(
        f"---\n{ref.dump_frontmatter(doc.frontmatter)}\n---\n"
        f"# {doc.frontmatter['title']}\n\n"
        f"{ref.render_meta_table(doc.frontmatter)}\n\n## Overview\nx\n",
        encoding="utf-8",
    )
    ref.write_index(tmp_path)
    assert any("ghost" in e for e in ref.validate_docs(tmp_path))


def test_add_tag_delegates_to_core(tmp_path):
    _registry(tmp_path, "architecture")
    ref.add_tag(tmp_path / "_tags.md", "domain-model", "the model")
    assert list(ref.load_tags(tmp_path / "_tags.md")) == ["architecture", "domain-model"]
