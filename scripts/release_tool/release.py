"""SemVer helpers for the release workflow.

Single source of truth for version math: parse/format/bump SemVer, pick the latest
release tag, and read/write the project version in pom.xml. Used by the bump-version CLI
(write) and by CI (verify), so the writer and the checker can never disagree.
"""

from __future__ import annotations

import re
from pathlib import Path

BUMP_KINDS = ("major", "minor", "patch")

# Leading optional "v", three numeric components; anything after (e.g. -SNAPSHOT) ignored.
_SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?!\.\d)")

# The project's own <version> is the one immediately following the zarlania-api
# <artifactId>. This deliberately does NOT match the <parent> version.
_POM_VERSION_RE = re.compile(
    r"(<artifactId>zarlania-api</artifactId>\s*<version>)(.*?)(</version>)"
)


def parse_version(text: str) -> tuple[int, int, int]:
    m = _SEMVER_RE.match(text.strip())
    if not m:
        raise ValueError(f"not a SemVer version: {text!r}")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)))


def format_version(version: tuple[int, int, int]) -> str:
    return f"{version[0]}.{version[1]}.{version[2]}"


def bump(version: tuple[int, int, int], kind: str) -> tuple[int, int, int]:
    major, minor, patch = version
    if kind == "major":
        return (major + 1, 0, 0)
    if kind == "minor":
        return (major, minor + 1, 0)
    if kind == "patch":
        return (major, minor, patch + 1)
    raise ValueError(f"unknown bump kind: {kind!r} (expected one of {BUMP_KINDS})")


def latest_tag_version(tags: list[str]) -> tuple[int, int, int]:
    """Return the highest SemVer among tags, or (0, 0, 0) if none parse."""
    versions = []
    for tag in tags:
        try:
            versions.append(parse_version(tag))
        except ValueError:
            continue  # ignore non-SemVer tags
    return max(versions) if versions else (0, 0, 0)


def expected_version(tags: list[str], kind: str) -> str:
    return format_version(bump(latest_tag_version(tags), kind))


def read_pom_version(pom_path: str | Path) -> str:
    """Return the project's <version> (not the parent's); raise if absent."""
    text = Path(pom_path).read_text(encoding="utf-8")
    m = _POM_VERSION_RE.search(text)
    if not m:
        raise ValueError(f"could not find project <version> in {pom_path}")
    return m.group(2).strip()


def set_pom_version(pom_path: str | Path, new_version: str) -> None:
    pom_path = Path(pom_path)
    text = pom_path.read_text(encoding="utf-8")
    new_text, n = _POM_VERSION_RE.subn(
        lambda m: f"{m.group(1)}{new_version}{m.group(3)}", text, count=1
    )
    if n != 1:
        raise ValueError(f"could not rewrite project <version> in {pom_path}")
    pom_path.write_text(new_text, encoding="utf-8")
