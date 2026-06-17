# Phase 5 — Release Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every merge to `master` produce exactly one SemVer release, with the
version bump committed *inside the PR* and guarded by CI so it can never be forgotten or
wrong.

**Architecture:** A small, tested Python `release` library is the single source of truth
for SemVer math (read/write the `pom.xml` project version, compute the next version from
the latest release tag + a bump kind). A `scripts/bump-version` CLI wraps it with three
subcommands: `bump` (write the next version into the pom — run in the PR), `verify`
(assert the pom matches the expected bump — run by CI on every PR), and `current` (print
the pom version — used by the release workflow). A new `release-check` CI job derives the
bump kind from the PR's `release:*` label and runs `verify`. A `release.yml` workflow runs
on push to `master`, reads the (already-verified) pom version, and creates the matching
`v<version>` git tag + GitHub Release with auto-generated notes — no new commit, so Render
deploys once.

**Tech Stack:** Python 3 (mirrors the existing `scripts/adr_tool` package + `scripts/adr`
bash wrapper pattern), pytest (already wired via `pyproject.toml` + the CI `checks` job),
GitHub Actions (SHA-pinned actions, least-privilege permissions), `gh` CLI, Maven
(`pom.xml <version>` is the SemVer source of truth, surfaced at `/actuator/info`).

**Governance for this phase:** Issue + branch `feat/<issue#>-release-automation` + PR
titled with `#<issue>`. This PR dogfoods the new model: it bumps `pom.xml` to the first
release version and carries a `release:minor` label (see Task 8). The maintainer accepts
nothing here (no ADR in this phase — the release ADR is Phase 6) and performs the merge +
adds the new CI jobs as required status checks.

**Key facts (verified at planning time):**

- Project version today: `pom.xml` line 13 → `<version>0.0.1-SNAPSHOT</version>` (the
  parent `spring-boot-starter-parent` version on line 8 is unrelated — do not touch it).
- No git tags, no GitHub releases, no `release:*` labels exist yet → first-release path
  (base version `0.0.0`).
- `pyproject.toml` currently: `pythonpath = ["scripts/adr_tool"]`,
  `testpaths = ["scripts/adr_tool/tests"]`,
  `addopts = "--cov=lib --cov=cli --cov-branch --cov-report=term-missing --cov-fail-under=80"`.
- The ADR wrapper `scripts/adr` resolves `../.venv/bin/python` then falls back to
  `python3` — mirror this exactly for `scripts/bump-version`.
- CI (`.github/workflows/ci.yml`) jobs: `build`, `checks`, `secrets`, `governance`.
  Actions are SHA-pinned; reuse these exact pins:
  - `actions/checkout` → `df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3`
  - `actions/setup-python` → `a309ff8b426b58ec0e2a45f0f869d46889d02405 # v6.2.0`
- The `governance` job exempts `dependabot[bot]`; the new release-check job does the same.

---

## File Structure

- `scripts/release_tool/release.py` — **new.** Pure SemVer/pom library (no I/O beyond
  reading/writing the pom file). One responsibility: version math + pom read/write.
- `scripts/release_tool/release_cli.py` — **new.** Argparse CLI (`bump`/`verify`/`current`)
  over `release.py`; reads git tags via subprocess. One responsibility: CLI wiring.
- `scripts/release_tool/tests/test_release.py` — **new.** Unit tests for `release.py`.
- `scripts/release_tool/tests/test_release_cli.py` — **new.** Tests for the CLI.
- `scripts/bump-version` — **new.** Bash wrapper (mirrors `scripts/adr`).
- `pyproject.toml` — **modify.** Add `release_tool` to `pythonpath`/`testpaths` and extend
  `--cov`.
- `.github/workflows/ci.yml` — **modify.** Add the `release-check` PR job.
- `.github/workflows/release.yml` — **new.** Tag + GitHub Release on merge to master.
- `.github/PULL_REQUEST_TEMPLATE.md` — **modify.** Add the release-bump checklist item.
- `CLAUDE.md` — **modify.** Add a "Releases" section so Claude bumps + labels in-PR.
- `README.md` / `CONTRIBUTING.md` — **modify.** Document the release process + the
  maintainer's required-checks setup.
- `pom.xml` — **modify (Task 8 only).** Set the first release version.

Module names `release` / `release_cli` are intentionally distinct from the adr_tool
modules (`lib` / `cli`) so both packages can sit on `pythonpath` without collision.

---

### Task 1: SemVer + pom version library

**Files:**
- Create: `scripts/release_tool/release.py`
- Test: `scripts/release_tool/tests/test_release.py`

- [ ] **Step 1: Write the failing tests**

Create `scripts/release_tool/tests/test_release.py`:

```python
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `pytest scripts/release_tool/tests/test_release.py -q`
Expected: collection/import error (`ModuleNotFoundError: No module named 'release'`) — the
module and pythonpath entry don't exist yet (pythonpath is added in Task 3; for this step
run with an explicit path override to confirm failure):
`PYTHONPATH=scripts/release_tool pytest scripts/release_tool/tests/test_release.py -q`
Expected now: FAIL with `AttributeError`/`ModuleNotFoundError` for `release.*`.

- [ ] **Step 3: Implement `release.py`**

Create `scripts/release_tool/release.py`:

```python
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
_SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)")

# The project's own <version> is the one immediately following the zarlania-api
# <artifactId>. This deliberately does NOT match the <parent> version.
_POM_VERSION_RE = re.compile(
    r"(<artifactId>zarlania-api</artifactId>\s*<version>)(.*?)(</version>)",
    re.DOTALL,
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
    versions = []
    for tag in tags:
        try:
            versions.append(parse_version(tag))
        except ValueError:
            continue  # ignore non-SemVer tags
    return max(versions) if versions else (0, 0, 0)


def expected_version(tags: list[str], kind: str) -> str:
    return format_version(bump(latest_tag_version(tags), kind))


def read_pom_version(pom_path) -> str:
    text = Path(pom_path).read_text(encoding="utf-8")
    m = _POM_VERSION_RE.search(text)
    if not m:
        raise ValueError(f"could not find project <version> in {pom_path}")
    return m.group(2).strip()


def set_pom_version(pom_path, new_version: str) -> None:
    pom_path = Path(pom_path)
    text = pom_path.read_text(encoding="utf-8")
    new_text, n = _POM_VERSION_RE.subn(
        lambda m: f"{m.group(1)}{new_version}{m.group(3)}", text, count=1
    )
    if n != 1:
        raise ValueError(f"could not rewrite project <version> in {pom_path}")
    pom_path.write_text(new_text, encoding="utf-8")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `PYTHONPATH=scripts/release_tool pytest scripts/release_tool/tests/test_release.py -q`
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add scripts/release_tool/release.py scripts/release_tool/tests/test_release.py
git commit -m "feat(release): add SemVer + pom version library (#<issue>)"
```

---

### Task 2: bump-version CLI

**Files:**
- Create: `scripts/release_tool/release_cli.py`
- Test: `scripts/release_tool/tests/test_release_cli.py`

- [ ] **Step 1: Write the failing tests**

Create `scripts/release_tool/tests/test_release_cli.py`:

```python
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `PYTHONPATH=scripts/release_tool pytest scripts/release_tool/tests/test_release_cli.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'release_cli'`.

- [ ] **Step 3: Implement `release_cli.py`**

Create `scripts/release_tool/release_cli.py`:

```python
"""CLI for the release version workflow. Run via ./scripts/bump-version."""

from __future__ import annotations

import argparse
import subprocess
import sys

import release


def _git_tags() -> list[str]:
    out = subprocess.run(
        ["git", "tag", "--list"], capture_output=True, text=True, check=True
    )
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def _cmd_current(args) -> int:
    print(release.read_pom_version(args.pom))
    return 0


def _cmd_bump(args) -> int:
    new_version = release.expected_version(_git_tags(), args.kind)
    release.set_pom_version(args.pom, new_version)
    print(new_version)
    return 0


def _cmd_verify(args) -> int:
    expected = release.expected_version(_git_tags(), args.kind)
    actual = release.read_pom_version(args.pom)
    if actual != expected:
        print(
            f"::error::pom version {actual} does not match the '{args.kind}' bump "
            f"(expected {expected} relative to the latest release tag). "
            f"Run ./scripts/bump-version bump {args.kind}.",
            file=sys.stderr,
        )
        return 1
    print(f"OK: pom version {actual} matches the '{args.kind}' bump.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="bump-version", description="Manage the release version.")
    p.add_argument("--pom", default="pom.xml", help="path to pom.xml")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("current", help="print the pom project version").set_defaults(fn=_cmd_current)

    b = sub.add_parser("bump", help="write the next version into the pom for <kind>")
    b.add_argument("kind", choices=release.BUMP_KINDS)
    b.set_defaults(fn=_cmd_bump)

    v = sub.add_parser("verify", help="assert the pom version matches the <kind> bump")
    v.add_argument("kind", choices=release.BUMP_KINDS)
    v.set_defaults(fn=_cmd_verify)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `PYTHONPATH=scripts/release_tool pytest scripts/release_tool/tests/test_release_cli.py -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/release_tool/release_cli.py scripts/release_tool/tests/test_release_cli.py
git commit -m "feat(release): add bump-version CLI (current/bump/verify) (#<issue>)"
```

---

### Task 3: Wire the package into pytest + the bash wrapper

**Files:**
- Modify: `pyproject.toml`
- Create: `scripts/bump-version`

- [ ] **Step 1: Update `pyproject.toml`**

Replace the `[tool.pytest.ini_options]` block so both packages are on the path and covered:

```toml
[tool.pytest.ini_options]
pythonpath = ["scripts/adr_tool", "scripts/release_tool"]
testpaths = ["scripts/adr_tool/tests", "scripts/release_tool/tests"]
addopts = "--cov=lib --cov=cli --cov=release --cov=release_cli --cov-branch --cov-report=term-missing --cov-fail-under=80"
```

- [ ] **Step 2: Create the bash wrapper `scripts/bump-version`**

```bash
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_PY="${HERE}/../.venv/bin/python"
if [[ -x "${VENV_PY}" ]]; then
  PY="${VENV_PY}"
else
  PY="python3"
fi
exec "${PY}" "${HERE}/release_tool/release_cli.py" "$@"
```

- [ ] **Step 3: Make it executable**

Run: `chmod +x scripts/bump-version`

- [ ] **Step 4: Run the full python test suite the way CI does**

Run: `pytest -q`
Expected: PASS — both `adr_tool` and `release_tool` tests collected, total coverage ≥ 80%.
(This confirms the `pythonpath`/`testpaths`/`--cov` wiring is correct and there is no
module-name collision between the two packages.)

- [ ] **Step 5: Smoke-test the wrapper end to end**

Run: `./scripts/bump-version current`
Expected: prints `0.0.1-SNAPSHOT` (the current pom version; do not commit any pom change
from this smoke test).

- [ ] **Step 6: Commit**

```bash
git add pyproject.toml scripts/bump-version
git commit -m "build(release): wire release_tool into pytest and add bump-version wrapper (#<issue>)"
```

---

### Task 4: PR-time CI check (release-check job)

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the `release-check` job**

Append this job to the `jobs:` map in `.github/workflows/ci.yml` (after `governance`):

```yaml
  release-check:
    name: Release version bump
    if: github.event_name == 'pull_request' && github.event.pull_request.user.login != 'dependabot[bot]'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          fetch-depth: 0 # need tags to compute the latest release version
          persist-credentials: false
      - name: Set up Python
        uses: actions/setup-python@a309ff8b426b58ec0e2a45f0f869d46889d02405 # v6.2.0
        with:
          python-version: "3.12"
      - name: Determine bump kind from release label
        id: bump
        env:
          LABELS: ${{ toJSON(github.event.pull_request.labels.*.name) }}
        run: |
          kinds=$(printf '%s' "$LABELS" | grep -oE 'release:(major|minor|patch)' | sed 's/release://' | sort -u || true)
          count=$(printf '%s\n' "$kinds" | grep -c . || true)
          if [ "$count" -gt 1 ]; then
            echo "::error::multiple release:* labels found ($(echo "$kinds" | tr '\n' ' ')); apply exactly one." >&2
            exit 1
          fi
          kind="${kinds:-patch}"
          echo "kind=$kind" >> "$GITHUB_OUTPUT"
          echo "Bump kind: $kind"
      - name: Verify pom version matches the bump
        run: python scripts/release_tool/release_cli.py verify "${{ steps.bump.outputs.kind }}"
```

- [ ] **Step 2: Validate the workflow YAML**

Run: `pre-commit run yamllint --files .github/workflows/ci.yml`
Expected: `yamllint ... Passed`.

- [ ] **Step 3: Walk through the logic (manual verification)**

Confirm by reading: (a) no `release:*` label → `kind=patch`; (b) exactly one →
that kind; (c) two release labels → the step exits 1 with a clear error; (d) the verify
step calls the same `release_cli.py verify` the developer's `bump-version` uses. Confirm
`fetch-depth: 0` is present (tags are required for `latest_tag_version`).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(release): verify in-PR pom bump matches the release label (#<issue>)"
```

---

### Task 5: Release workflow (tag + GitHub Release on merge)

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create `.github/workflows/release.yml`**

```yaml
name: Release

on:
  push:
    branches: [master]

# Only the release job needs to create tags/releases.
permissions:
  contents: write

# Serialize releases; never cancel an in-flight one.
concurrency:
  group: release
  cancel-in-progress: false

jobs:
  release:
    name: Tag and GitHub Release
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          fetch-depth: 0
          persist-credentials: false
      - name: Set up Python
        uses: actions/setup-python@a309ff8b426b58ec0e2a45f0f869d46889d02405 # v6.2.0
        with:
          python-version: "3.12"
      - name: Read pom version
        id: ver
        run: echo "version=$(python scripts/release_tool/release_cli.py current)" >> "$GITHUB_OUTPUT"
      - name: Create tag and GitHub Release if new
        env:
          GH_TOKEN: ${{ github.token }}
          VERSION: ${{ steps.ver.outputs.version }}
        run: |
          tag="v${VERSION}"
          if gh release view "$tag" >/dev/null 2>&1; then
            echo "Release $tag already exists; nothing to do (changes ride the next bump)."
            exit 0
          fi
          gh release create "$tag" --target "$GITHUB_SHA" --title "$tag" --generate-notes
          echo "Created release $tag."
```

- [ ] **Step 2: Validate the workflow YAML**

Run: `pre-commit run yamllint --files .github/workflows/release.yml`
Expected: `yamllint ... Passed`.

- [ ] **Step 3: Walk through the logic (manual verification)**

Confirm by reading: (a) triggers only on push to `master` (i.e. merges); (b)
`gh release create` with a non-existent tag creates the tag at `GITHUB_SHA` via the API —
no `git push`, so no second commit and Render deploys once; (c) `--generate-notes`
produces the changelog-equivalent; (d) the idempotency guard makes un-bumped merges (e.g.
Dependabot) a no-op. Confirm `permissions: contents: write` is present (required to create
releases/tags).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): tag and cut a GitHub Release on merge to master (#<issue>)"
```

---

### Task 6: PR template + CLAUDE.md release guidance

**Files:**
- Modify: `.github/PULL_REQUEST_TEMPLATE.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add the release item to the PR template**

In `.github/PULL_REQUEST_TEMPLATE.md`, add this item to the `## Checklist` list (after the
ADR item):

```markdown
- [ ] Release: `pom.xml` `<version>` is bumped for this change. Set the `release:major|minor|patch` label (unlabeled = patch) and run `./scripts/bump-version bump <kind>`.
```

- [ ] **Step 2: Add a Releases section to CLAUDE.md**

In `CLAUDE.md`, add this section immediately after the `## Non-negotiables` section:

```markdown
## Releases (every merge ships)

Every merge to `master` cuts exactly one SemVer release. The version lives in `pom.xml`
`<version>` and is bumped **inside the PR** (never after merge — that would double-deploy).

When opening a PR:

1. Choose the bump from the change: breaking = `major`, feature = `minor`, fix/chore =
   `patch`. Apply the matching `release:<kind>` label (no label = `patch`).
2. Run `./scripts/bump-version bump <kind>` to set `pom.xml` to the next version.
3. CI's "Release version bump" check verifies the pom matches the label vs. the latest
   release tag; on merge, `release.yml` tags `v<version>` and cuts the GitHub Release.
```

- [ ] **Step 3: Verify markdown lint passes**

Run: `pre-commit run markdownlint --files .github/PULL_REQUEST_TEMPLATE.md CLAUDE.md`
Expected: `markdownlint ... Passed`.

- [ ] **Step 4: Commit**

```bash
git add .github/PULL_REQUEST_TEMPLATE.md CLAUDE.md
git commit -m "docs(release): document the in-PR bump model for contributors and Claude (#<issue>)"
```

---

### Task 7: README + CONTRIBUTING release docs (incl. maintainer setup)

**Files:**
- Modify: `README.md`
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Read both files first**

Run: `sed -n '1,80p' README.md` and `sed -n '1,120p' CONTRIBUTING.md` to find the right
insertion points (a commands/sections area in README; a workflow section in CONTRIBUTING).
Match the surrounding heading style and tone.

- [ ] **Step 2: Add a release section to CONTRIBUTING.md**

Add a `## Releasing` section with this content (place it after the existing
workflow/PR section):

```markdown
## Releasing

Releases are automated — every merge to `master` produces one SemVer release; there is no
manual release step and no `CHANGELOG.md` (GitHub Release notes are auto-generated).

The version is `pom.xml` `<version>` and must be bumped **in the PR**:

1. Apply one `release:major`, `release:minor`, or `release:patch` label (no label = patch).
2. Run `./scripts/bump-version bump <kind>` to write the next version.
3. CI rejects the PR if the pom version doesn't match the label relative to the latest
   release tag.

On merge, `release.yml` creates the `v<version>` tag and GitHub Release. Dependabot PRs are
not expected to bump; their changes are included in the next release that does.
```

- [ ] **Step 3: Add a maintainer setup note to README.md**

Add (or extend) a maintainer/admin note documenting one-time GitHub setup. Use this text:

```markdown
### Maintainer: release setup (one-time)

- Create the bump labels:
  - `gh label create release:major --color B60205 --description "Release: major version bump"`
  - `gh label create release:minor --color FBCA04 --description "Release: minor version bump"`
  - `gh label create release:patch --color 0E8A16 --description "Release: patch version bump"`
- Add **Release version bump** and the existing CI jobs to the required status checks for
  `master` (Settings → Branches → branch protection).
```

- [ ] **Step 4: Verify markdown lint passes**

Run: `pre-commit run markdownlint --files README.md CONTRIBUTING.md`
Expected: `markdownlint ... Passed`.

- [ ] **Step 5: Commit**

```bash
git add README.md CONTRIBUTING.md
git commit -m "docs(release): document the release flow and maintainer label/branch setup (#<issue>)"
```

---

### Task 8: Dogfood — bump this PR to the first release version

This task makes the Phase 5 PR itself comply with the new model so the release-check job
passes and the first merge cuts `v0.1.0`.

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Set the first release version via the new tool**

Run: `./scripts/bump-version bump minor`
Expected output: `0.1.0` (no tags exist → base `0.0.0`, minor bump). This also drops the
`-SNAPSHOT` suffix, which is intended: master carries plain release versions.

- [ ] **Step 2: Confirm the pom changed correctly**

Run: `./scripts/bump-version current`
Expected: `0.1.0`. Also confirm the parent version is untouched: `grep -n "<version>" pom.xml`
should still show `4.1.0` for the parent and `0.1.0` for the project.

- [ ] **Step 3: Verify the bump matches a `minor` label locally**

Run: `./scripts/bump-version verify minor`
Expected: `OK: pom version 0.1.0 matches the 'minor' bump.`

- [ ] **Step 4: Full local build (the version feeds /actuator/info via build-info)**

Run: `./mvnw -q clean verify`
Expected: BUILD SUCCESS (the version change is benign; this confirms the pom is still valid
and the build-info goal still resolves the version).

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "release: set first release version 0.1.0 (#<issue>)"
```

- [ ] **Step 6: Reminder for PR creation (not a code step)**

When the PR is opened it MUST carry the `release:minor` label (created in Task 7 / by the
maintainer) so the `release-check` job computes `expected = 0.1.0` and passes.

---

## Final verification (after all tasks)

- [ ] `pytest -q` → all tests pass, coverage ≥ 80% (adr_tool + release_tool).
- [ ] `./mvnw -q clean verify` → BUILD SUCCESS.
- [ ] `SKIP=java-quality pre-commit run --all-files` → all hooks pass (yamllint,
  markdownlint, ruff, etc.).
- [ ] `./scripts/bump-version verify minor` → OK (pom = 0.1.0).
- [ ] Re-read `ci.yml` `release-check` and `release.yml` once more for the SHA-pinned
  actions and least-privilege `permissions`.

## Maintainer / out-of-band steps (cannot be done in-PR)

1. Create the three `release:*` labels (commands in Task 7) — required before the PR's
   `release-check` can find the `release:minor` label.
2. Apply the `release:minor` label to this PR.
3. After merge, add **Release version bump** to the required status checks on `master`.
4. Confirm the first run of `release.yml` created tag/release `v0.1.0` with generated notes.

## Notes / deliberate decisions

- **No auto-bump on merge.** The version in the PR diff *is* the release version; merge
  only tags it. This keeps the single-deploy guarantee (no post-merge commit).
- **Dependabot.** Dependabot PRs don't bump (exempt from `release-check`); their changes
  ride the next bumping release. `release.yml`'s idempotency guard makes their merge a
  no-op release-wise.
- **Plain versions (no `-SNAPSHOT`).** Master holds release versions; `-SNAPSHOT` is
  dropped at the first bump (Task 8).
- **The release ADR is Phase 6**, not this phase (per the agreed sequencing). Do not write
  an ADR here.
```
