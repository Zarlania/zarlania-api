from pathlib import Path

import ref
import ref_cli


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
        f"{ref.META_START}\n| Field | Value |\n| --- | --- |\n{ref.META_END}\n\n"
        "## Overview\n\nx\n"
    )
    (d / "_template.md").write_text(content, encoding="utf-8")


def _new(d: Path, title: str, tags: str = "architecture"):
    return ref_cli.main(["--ref-dir", str(d), "new", "--title", title, "--tags", tags])


def test_cli_new_then_index_then_check_passes(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    assert _new(tmp_path, "First Doc") == 0
    assert ref_cli.main(["--ref-dir", str(tmp_path), "index"]) == 0
    assert ref_cli.main(["--ref-dir", str(tmp_path), "check"]) == 0


def test_cli_new_missing_registry_exits_1(tmp_path):
    assert _new(tmp_path, "X") == 1


def test_cli_new_unknown_tag_exits_1(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    assert _new(tmp_path, "Bad", tags="ghost") == 1
    assert "ghost" in capsys.readouterr().err


def test_cli_new_missing_template_exits_1(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    assert _new(tmp_path, "No Template") == 1
    assert "_template.md" in capsys.readouterr().err


def test_cli_list_find_show_by_tag(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    _new(tmp_path, "Searchable Thing")
    capsys.readouterr()
    ref_cli.main(["--ref-dir", str(tmp_path), "list"])
    out = capsys.readouterr().out
    assert "000001" in out and "Searchable Thing" in out
    ref_cli.main(["--ref-dir", str(tmp_path), "find", "Searchable"])
    assert "000001" in capsys.readouterr().out
    ref_cli.main(["--ref-dir", str(tmp_path), "show", "000001"])
    assert "Searchable Thing" in capsys.readouterr().out
    ref_cli.main(["--ref-dir", str(tmp_path), "by-tag", "architecture"])
    assert "000001" in capsys.readouterr().out


def test_cli_list_filter_by_tag(tmp_path, capsys):
    _registry(tmp_path, "architecture", "domain-model")
    _template(tmp_path)
    _new(tmp_path, "Arch Doc", tags="architecture")
    capsys.readouterr()
    ref_cli.main(["--ref-dir", str(tmp_path), "list", "--tag", "architecture"])
    assert "000001" in capsys.readouterr().out
    ref_cli.main(["--ref-dir", str(tmp_path), "list", "--tag", "domain-model"])
    assert "000001" not in capsys.readouterr().out


def test_cli_add_tag_tags_and_tag_usage(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    ref_cli.main(["--ref-dir", str(tmp_path), "add-tag", "domain-model", "--description", "dm"])
    ref_cli.main(["--ref-dir", str(tmp_path), "tags"])
    assert "domain-model" in capsys.readouterr().out
    _new(tmp_path, "Tagged")
    ref_cli.main(["--ref-dir", str(tmp_path), "tag-usage"])
    out = capsys.readouterr().out
    assert "architecture" in out and "domain-model" in out


def test_cli_add_tag_missing_registry_exits_1(tmp_path, capsys):
    assert ref_cli.main(["--ref-dir", str(tmp_path), "add-tag", "x", "--description", "d"]) == 1
    assert "_tags.md" in capsys.readouterr().err


def test_cli_add_tag_duplicate_returns_0(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    capsys.readouterr()
    rc = ref_cli.main(["--ref-dir", str(tmp_path), "add-tag", "architecture", "--description", "x"])
    assert rc == 0
    assert "already registered" in capsys.readouterr().out


def test_cli_add_tag_with_pipe_exits_1(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    rc = ref_cli.main(["--ref-dir", str(tmp_path), "add-tag", "bad|tag", "--description", "d"])
    assert rc == 1
    assert "error" in capsys.readouterr().err.lower()


def test_cli_show_nonexistent_exits_1(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    assert ref_cli.main(["--ref-dir", str(tmp_path), "show", "999999"]) == 1
    assert "999999" in capsys.readouterr().err


def test_cli_tags_missing_registry_exits_1(tmp_path):
    assert ref_cli.main(["--ref-dir", str(tmp_path), "tags"]) == 1


def test_cli_check_fails_on_updated_before_created(tmp_path):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    path = ref.new_doc(tmp_path, title="One", tags=["architecture"], today="2026-06-21")
    doc = ref.parse_doc(path)
    doc.frontmatter["updated"] = "2026-06-20"
    path.write_text(
        f"---\n{ref.dump_frontmatter(doc.frontmatter)}\n---\n"
        f"# One\n\n{ref.render_meta_table(doc.frontmatter)}\n\n## Overview\nx\n",
        encoding="utf-8",
    )
    ref_cli.main(["--ref-dir", str(tmp_path), "index"])
    assert ref_cli.main(["--ref-dir", str(tmp_path), "check"]) == 1


def test_cli_check_success_prints_passed(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    _new(tmp_path, "Check Me")
    ref_cli.main(["--ref-dir", str(tmp_path), "index"])
    capsys.readouterr()
    assert ref_cli.main(["--ref-dir", str(tmp_path), "check"]) == 0
    assert "passed" in capsys.readouterr().out


def test_cli_index_prints_regenerated(tmp_path, capsys):
    _registry(tmp_path, "architecture")
    _template(tmp_path)
    ref.new_doc(tmp_path, title="Idx", tags=["architecture"])
    capsys.readouterr()
    assert ref_cli.main(["--ref-dir", str(tmp_path), "index"]) == 0
    assert "regenerated" in capsys.readouterr().out
