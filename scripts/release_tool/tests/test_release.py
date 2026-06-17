import textwrap

import pytest
import release


def test_parse_version_plain():
    assert release.parse_version("1.2.3") == (1, 2, 3)


def test_parse_version_strips_v_prefix_and_suffix():
    assert release.parse_version("v1.2.3") == (1, 2, 3)
    assert release.parse_version("1.2.3-SNAPSHOT") == (1, 2, 3)


def test_parse_version_rejects_garbage():
    with pytest.raises(ValueError):
        release.parse_version("not-a-version")


def test_format_version():
    assert release.format_version((1, 2, 3)) == "1.2.3"


def test_bump_major_minor_patch():
    assert release.bump((1, 2, 3), "major") == (2, 0, 0)
    assert release.bump((1, 2, 3), "minor") == (1, 3, 0)
    assert release.bump((1, 2, 3), "patch") == (1, 2, 4)


def test_bump_rejects_unknown_kind():
    with pytest.raises(ValueError):
        release.bump((1, 2, 3), "huge")


def test_latest_tag_version_empty_is_zero():
    assert release.latest_tag_version([]) == (0, 0, 0)


def test_latest_tag_version_numeric_not_lexical():
    # 0.10.0 must beat 0.2.0 (lexical sort would pick 0.2.0)
    assert release.latest_tag_version(["v0.2.0", "v0.10.0", "v0.9.0"]) == (0, 10, 0)


def test_latest_tag_version_ignores_non_semver_tags():
    assert release.latest_tag_version(["nightly", "v1.0.0", "release-candidate"]) == (1, 0, 0)


def test_expected_version_first_release():
    assert release.expected_version([], "minor") == "0.1.0"
    assert release.expected_version([], "patch") == "0.0.1"
    assert release.expected_version([], "major") == "1.0.0"


def test_expected_version_from_latest_tag():
    assert release.expected_version(["v1.4.2"], "patch") == "1.4.3"


def _pom(tmp_path, version):
    p = tmp_path / "pom.xml"
    p.write_text(
        textwrap.dedent(
            f"""\
            <project>
              <parent>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>4.1.0</version>
              </parent>
              <artifactId>zarlania-api</artifactId>
              <version>{version}</version>
            </project>
            """
        ),
        encoding="utf-8",
    )
    return p


def test_read_pom_version_reads_project_not_parent(tmp_path):
    p = _pom(tmp_path, "0.0.1-SNAPSHOT")
    assert release.read_pom_version(p) == "0.0.1-SNAPSHOT"


def test_set_pom_version_updates_project_not_parent(tmp_path):
    p = _pom(tmp_path, "0.0.1-SNAPSHOT")
    release.set_pom_version(p, "0.1.0")
    assert release.read_pom_version(p) == "0.1.0"
    assert "<version>4.1.0</version>" in p.read_text(encoding="utf-8")  # parent untouched


def test_read_pom_version_raises_when_missing(tmp_path):
    p = tmp_path / "pom.xml"
    p.write_text("<project></project>", encoding="utf-8")
    with pytest.raises(ValueError):
        release.read_pom_version(p)


def test_parse_version_rejects_four_part():
    with pytest.raises(ValueError):
        release.parse_version("1.2.3.4")


def test_set_pom_version_raises_when_missing(tmp_path):
    p = tmp_path / "pom.xml"
    p.write_text("<project></project>", encoding="utf-8")
    with pytest.raises(ValueError):
        release.set_pom_version(p, "1.0.0")
