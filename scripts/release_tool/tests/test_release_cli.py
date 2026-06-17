import textwrap

import release
import release_cli


def _pom(tmp_path, version):
    p = tmp_path / "pom.xml"
    p.write_text(
        textwrap.dedent(
            f"""\
            <project>
              <artifactId>zarlania-api</artifactId>
              <version>{version}</version>
            </project>
            """
        ),
        encoding="utf-8",
    )
    return p


def test_current_prints_pom_version(tmp_path, capsys, monkeypatch):
    p = _pom(tmp_path, "1.2.3")
    rc = release_cli.main(["--pom", str(p), "current"])
    assert rc == 0
    assert capsys.readouterr().out.strip() == "1.2.3"


def test_bump_writes_next_version_and_prints_it(tmp_path, capsys, monkeypatch):
    p = _pom(tmp_path, "0.0.1-SNAPSHOT")
    monkeypatch.setattr(release_cli, "_git_tags", lambda: ["v0.3.0"])
    rc = release_cli.main(["--pom", str(p), "bump", "minor"])
    assert rc == 0
    assert capsys.readouterr().out.strip() == "0.4.0"
    assert release.read_pom_version(p) == "0.4.0"


def test_bump_first_release_uses_zero_base(tmp_path, capsys, monkeypatch):
    p = _pom(tmp_path, "0.0.1-SNAPSHOT")
    monkeypatch.setattr(release_cli, "_git_tags", lambda: [])
    rc = release_cli.main(["--pom", str(p), "bump", "minor"])
    assert rc == 0
    assert release.read_pom_version(p) == "0.1.0"


def test_verify_passes_when_pom_matches(tmp_path, capsys, monkeypatch):
    p = _pom(tmp_path, "1.3.0")
    monkeypatch.setattr(release_cli, "_git_tags", lambda: ["v1.2.0"])
    rc = release_cli.main(["--pom", str(p), "verify", "minor"])
    assert rc == 0
    assert "OK" in capsys.readouterr().out


def test_verify_fails_when_pom_mismatched(tmp_path, capsys, monkeypatch):
    p = _pom(tmp_path, "1.2.1")  # patch bump, but label says minor -> expected 1.3.0
    monkeypatch.setattr(release_cli, "_git_tags", lambda: ["v1.2.0"])
    rc = release_cli.main(["--pom", str(p), "verify", "minor"])
    assert rc == 1
    assert "does not match" in capsys.readouterr().err
