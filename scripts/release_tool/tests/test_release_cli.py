import textwrap
from types import SimpleNamespace

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


def test_current_prints_pom_version(tmp_path, capsys):
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


def test_cli_reports_error_on_bad_pom(tmp_path, capsys):
    p = tmp_path / "pom.xml"
    p.write_text("<project></project>", encoding="utf-8")
    rc = release_cli.main(["--pom", str(p), "current"])
    assert rc == 1
    assert "error:" in capsys.readouterr().err


def test_git_tags_returns_stripped_nonempty(monkeypatch):
    monkeypatch.setattr(
        release_cli.subprocess,
        "run",
        lambda *a, **k: SimpleNamespace(stdout="v1.0.0\n\n v1.1.0 \n"),
    )
    assert release_cli._git_tags() == ["v1.0.0", "v1.1.0"]


def test_git_tags_warns_on_shallow_clone(capsys, monkeypatch):
    results = iter([SimpleNamespace(stdout=""), SimpleNamespace(stdout="true\n")])
    monkeypatch.setattr(release_cli.subprocess, "run", lambda *a, **k: next(results))
    assert release_cli._git_tags() == []
    assert "shallow" in capsys.readouterr().err


def test_git_tags_no_warning_when_not_shallow(capsys, monkeypatch):
    results = iter([SimpleNamespace(stdout=""), SimpleNamespace(stdout="false\n")])
    monkeypatch.setattr(release_cli.subprocess, "run", lambda *a, **k: next(results))
    assert release_cli._git_tags() == []
    assert "shallow" not in capsys.readouterr().err
