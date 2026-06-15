from pathlib import Path

import cli
import lib


def _registry(d: Path, *tags):
    rows = "".join(f"| {t} | desc |\n" for t in tags)
    (d / "_tags.md").write_text(
        "# ADR Tags\n\n| Tag | Description |\n| --- | --- |\n" + rows, encoding="utf-8"
    )


def test_cli_new_then_check_passes(tmp_path, capsys):
    _registry(tmp_path, "process")
    rc = cli.main(
        [
            "--adr-dir",
            str(tmp_path),
            "new",
            "--name",
            "First",
            "--tags",
            "process",
            "--author",
            "stimothy",
        ]
    )
    assert rc == 0
    cli.main(["--adr-dir", str(tmp_path), "index"])
    rc = cli.main(["--adr-dir", str(tmp_path), "check"])
    assert rc == 0


def test_cli_check_fails_on_unknown_tag(tmp_path):
    _registry(tmp_path, "process")
    # bypass the `new` command's tag guard to simulate an ADR with an unregistered tag
    lib.new_adr(tmp_path, name="Bad", tags=["ghost"], author="stimothy")
    cli.main(["--adr-dir", str(tmp_path), "index"])
    rc = cli.main(["--adr-dir", str(tmp_path), "check"])
    assert rc == 1


def test_cli_list_and_by_tag(tmp_path, capsys):
    _registry(tmp_path, "process")
    cli.main(
        [
            "--adr-dir",
            str(tmp_path),
            "new",
            "--name",
            "Listed",
            "--tags",
            "process",
            "--author",
            "stimothy",
        ]
    )
    cli.main(["--adr-dir", str(tmp_path), "list"])
    out = capsys.readouterr().out
    assert "0001" in out and "Listed" in out
    cli.main(["--adr-dir", str(tmp_path), "by-tag", "process"])
    assert "0001" in capsys.readouterr().out


def test_cli_add_tag_and_tags(tmp_path, capsys):
    _registry(tmp_path, "process")
    cli.main(["--adr-dir", str(tmp_path), "add-tag", "security", "--description", "sec"])
    cli.main(["--adr-dir", str(tmp_path), "tags"])
    assert "security" in capsys.readouterr().out


def test_cli_find_and_show(tmp_path, capsys):
    _registry(tmp_path, "process")
    cli.main(
        [
            "--adr-dir",
            str(tmp_path),
            "new",
            "--name",
            "Searchable Thing",
            "--tags",
            "process",
            "--author",
            "stimothy",
        ]
    )
    cli.main(["--adr-dir", str(tmp_path), "find", "Searchable"])
    assert "0001" in capsys.readouterr().out
    cli.main(["--adr-dir", str(tmp_path), "show", "0001"])
    assert "Searchable Thing" in capsys.readouterr().out


def test_cli_accept(tmp_path):
    _registry(tmp_path, "process")
    cli.main(
        [
            "--adr-dir",
            str(tmp_path),
            "new",
            "--name",
            "Accept Me",
            "--tags",
            "process",
            "--author",
            "stimothy",
        ]
    )
    cli.main(["--adr-dir", str(tmp_path), "accept", "0001"])
    adr = lib.parse_adr(tmp_path / "0001-accept-me.md")
    assert adr.frontmatter["status"] == "accepted"
