# Phase 1 — ADR Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Architecture Decision Record system — storage layout, machine+human dual format with drift protection, a tested Python `./scripts/adr` CLI, Claude skills, ADR-0001 (the ADR process itself), and CLAUDE.md/README skeletons.

**Architecture:** ADRs live in `docs/adrs/` as Markdown files whose **YAML frontmatter is the single source of truth**. A human-readable metadata table is *rendered from* the frontmatter and delimited by HTML comment markers, so a `check` command can re-render and string-compare to detect drift. A central `_tags.md` registry constrains tags. All operations go through one Python CLI (`./scripts/adr`) with subcommands; three Claude skills wrap it for token-efficient ADR create/search/tags work.

**Tech Stack:** Python 3.11+ (PyYAML), pytest + pytest-cov + ruff, Bash wrapper, Markdown.

**Process:** This phase is built on a feature branch off `master`, tied to a GitHub issue, and merged via PR — honoring the repo workflow manually before CI enforces it (per spec §13 bootstrap).

**Spec:** `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` (§2, §3, §13 Phase 1).

---

## File Structure

| File | Responsibility |
|---|---|
| `pyproject.toml` | Python dev tooling config: pytest (pythonpath, coverage gate), ruff. |
| `requirements-dev.txt` | Python dev dependencies (pyyaml, pytest, pytest-cov, ruff). |
| `scripts/adr/lib.py` | Pure ADR logic: parse/serialize frontmatter, render meta table + index, drift/schema/tag validation, id/slug helpers. No I/O side effects beyond reading files. |
| `scripts/adr/cli.py` | Argparse CLI dispatching subcommands to `lib`; the only place that writes files / prints. |
| `scripts/adr/__init__.py` | Marks package (empty). |
| `scripts/adr` | Bash wrapper → `python3 scripts/adr/cli.py`. |
| `scripts/adr/tests/test_lib.py` | Unit tests for `lib`. |
| `scripts/adr/tests/test_cli.py` | Tests for `cli.main()` against a temp ADR dir. |
| `docs/adrs/_template.md` | Canonical ADR template (frontmatter + rendered table + MADR sections). |
| `docs/adrs/_tags.md` | Tag registry table. |
| `docs/adrs/README.md` | Generated ADR index. |
| `docs/adrs/0001-record-architecture-decisions.md` | ADR-0001: the ADR process. |
| `.claude/skills/adr-create/SKILL.md` | Skill: create a one-subject ADR. |
| `.claude/skills/adr-search/SKILL.md` | Skill: token-efficient ADR lookup. |
| `.claude/skills/adr-tags/SKILL.md` | Skill: inspect/reuse/add tags. |
| `CLAUDE.md` | AI entry point (skeleton; grows in later phases). |
| `README.md` | Human entry point (skeleton; grows in later phases). |

**CLI subcommand → spec script mapping:** `new`→new-adr, `list`→list-adrs, `find`→find-adrs, `show`→show-adr, `tags`→list-tags, `add-tag`→add-tag, `tag-usage`→tag-usage, `by-tag`→adrs-by-tag, `accept`→accept-adr, `index`→(regenerate index), `check`→check-adrs.

---

## Task 0: Issue + branch

- [ ] **Step 1: Create the GitHub issue**

```bash
gh issue create \
  --title "Phase 1: ADR foundation (tooling, skills, ADR-0001)" \
  --body "Implements Phase 1 of the repo-shell spec (docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md §2,§3): docs/adrs layout, ./scripts/adr CLI with drift check, Claude skills, ADR-0001, CLAUDE.md/README skeletons."
```

Note the issue number printed (referenced below as `<ISSUE#>`).

- [ ] **Step 2: Create the branch**

```bash
git switch -c "feat/<ISSUE#>-adr-foundation"
```

Replace `<ISSUE#>` with the real number.

---

## Task 1: Python tooling + package skeleton

**Files:**
- Create: `requirements-dev.txt`
- Create: `pyproject.toml`
- Create: `scripts/adr/__init__.py`
- Create: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Create `requirements-dev.txt`**

```text
pyyaml==6.0.2
pytest==8.3.4
pytest-cov==6.0.0
ruff==0.9.2
```

- [ ] **Step 2: Create `pyproject.toml`**

```toml
[tool.pytest.ini_options]
pythonpath = ["scripts/adr"]
testpaths = ["scripts/adr/tests"]
addopts = "--cov=lib --cov=cli --cov-branch --cov-report=term-missing"

[tool.ruff]
line-length = 100
target-version = "py311"

[tool.ruff.lint]
select = ["E", "F", "I", "UP", "B"]
```

- [ ] **Step 3: Create the venv and install deps**

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
```

- [ ] **Step 4: Create `scripts/adr/__init__.py` (empty) and a minimal `scripts/adr/lib.py`**

`scripts/adr/__init__.py`: empty file.

`scripts/adr/lib.py`:

```python
"""Library for managing Architecture Decision Records (ADRs)."""
from __future__ import annotations

import re

# Ordered ADR metadata fields mapped to their human-readable table labels.
FIELD_LABELS: dict[str, str] = {
    "id": "ID",
    "name": "Name",
    "description": "Description",
    "status": "Status",
    "date_proposed": "Date proposed",
    "date_accepted": "Date accepted",
    "date_invalidated": "Date invalidated",
    "author": "Author",
    "supersedes": "Supersedes",
    "superseded_by": "Superseded by",
    "tags": "Tags",
}

VALID_STATUSES = {"proposed", "accepted", "superseded", "deprecated", "rejected"}
LIST_FIELDS = {"supersedes", "superseded_by", "tags"}


def slugify(name: str) -> str:
    """Lowercase, hyphenate, strip to a filename-safe slug."""
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
```

- [ ] **Step 5: Write the failing test**

`scripts/adr/tests/test_lib.py`:

```python
import lib


def test_slugify_basic():
    assert lib.slugify("Use Postgres for Persistence!") == "use-postgres-for-persistence"


def test_slugify_collapses_separators():
    assert lib.slugify("  A / B  --  C ") == "a-b-c"


def test_field_labels_cover_schema():
    assert lib.FIELD_LABELS["id"] == "ID"
    assert "tags" in lib.FIELD_LABELS
    assert len(lib.FIELD_LABELS) == 11
```

- [ ] **Step 6: Run tests — expect FAIL then PASS**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -v
```

Expected: PASS (3 passed). Coverage is reported but not gated until Task 9.

- [ ] **Step 7: Commit**

```bash
git add pyproject.toml requirements-dev.txt scripts/adr/
git commit -m "feat: scaffold ADR python tooling (#<ISSUE#>)"
```

---

## Task 2: Frontmatter parse + serialize

**Files:**
- Modify: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Write the failing tests**

Append to `scripts/adr/tests/test_lib.py`:

```python
import textwrap
from pathlib import Path


def _write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "0001-x.md"
    p.write_text(textwrap.dedent(text), encoding="utf-8")
    return p


def test_parse_adr_splits_frontmatter_and_body(tmp_path):
    p = _write(tmp_path, """\
        ---
        id: "0001"
        name: Example
        tags: [a, b]
        ---
        # Body here
        text
        """)
    adr = lib.parse_adr(p)
    assert adr.frontmatter["id"] == "0001"
    assert adr.frontmatter["tags"] == ["a", "b"]
    assert adr.body.startswith("# Body here")


def test_parse_adr_without_frontmatter_raises(tmp_path):
    p = tmp_path / "0002-y.md"
    p.write_text("no frontmatter\n", encoding="utf-8")
    try:
        lib.parse_adr(p)
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_dump_frontmatter_orders_fields():
    fm = {"name": "N", "id": "0007", "tags": ["x"]}
    out = lib.dump_frontmatter(fm)
    lines = out.splitlines()
    assert lines[0].startswith("id:")
    assert lines[1].startswith("name:")
    assert "tags:" in out
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "parse_adr or dump_frontmatter" -v
```

Expected: FAIL (`AttributeError: module 'lib' has no attribute 'parse_adr'`).

- [ ] **Step 3: Implement in `scripts/adr/lib.py`**

Add imports at top (under existing `import re`):

```python
from dataclasses import dataclass
from pathlib import Path

import yaml
```

Add the frontmatter regex constant near the other module constants:

```python
FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n?(.*)$", re.DOTALL)
```

Add at the end of the file:

```python
@dataclass
class Adr:
    path: Path
    frontmatter: dict
    body: str


def parse_adr(path) -> "Adr":
    text = Path(path).read_text(encoding="utf-8")
    m = FRONTMATTER_RE.match(text)
    if not m:
        raise ValueError(f"{path}: missing or malformed YAML frontmatter")
    fm = yaml.safe_load(m.group(1)) or {}
    return Adr(path=Path(path), frontmatter=fm, body=m.group(2))


def dump_frontmatter(fm: dict) -> str:
    """Serialize frontmatter in canonical field order (only known fields)."""
    ordered = {key: fm.get(key) for key in FIELD_LABELS}
    return yaml.safe_dump(ordered, sort_keys=False, allow_unicode=True).rstrip()
```

- [ ] **Step 4: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "parse_adr or dump_frontmatter" -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/adr/lib.py scripts/adr/tests/test_lib.py
git commit -m "feat: parse and serialize ADR frontmatter (#<ISSUE#>)"
```

---

## Task 3: Render + extract meta table (drift core)

**Files:**
- Modify: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Write the failing tests**

Append to `scripts/adr/tests/test_lib.py`:

```python
def test_display_value_handles_empty_and_lists():
    assert lib.display_value("author", None) == "—"
    assert lib.display_value("tags", []) == "—"
    assert lib.display_value("tags", ["a", "b"]) == "a, b"
    assert lib.display_value("status", "accepted") == "accepted"


def test_render_meta_table_roundtrips_via_extract():
    fm = {
        "id": "0001", "name": "Example", "description": "d", "status": "proposed",
        "date_proposed": "2026-06-15", "date_accepted": None, "date_invalidated": None,
        "author": "stimothy", "supersedes": [], "superseded_by": [], "tags": ["process"],
    }
    table = lib.render_meta_table(fm)
    assert table.startswith(lib.META_START)
    assert table.rstrip().endswith(lib.META_END)
    body = f"intro\n\n{table}\n\n## Section\n"
    assert lib.extract_meta_table(body) == table


def test_extract_meta_table_missing_returns_none():
    assert lib.extract_meta_table("no markers here") is None
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "display_value or meta_table" -v
```

Expected: FAIL (`AttributeError: ... 'display_value'`).

- [ ] **Step 3: Implement in `scripts/adr/lib.py`**

Add constants near the others:

```python
META_START = "<!-- adr-meta:start -->"
META_END = "<!-- adr-meta:end -->"
EMPTY_DISPLAY = "—"
```

Add functions at end of file:

```python
def display_value(field_name: str, value) -> str:
    if value is None or value == "" or value == []:
        return EMPTY_DISPLAY
    if field_name in LIST_FIELDS:
        if isinstance(value, list):
            return ", ".join(str(v) for v in value) if value else EMPTY_DISPLAY
        return str(value)
    return str(value)


def render_meta_table(fm: dict) -> str:
    lines = [META_START, "| Field | Value |", "| --- | --- |"]
    for key, label in FIELD_LABELS.items():
        lines.append(f"| {label} | {display_value(key, fm.get(key))} |")
    lines.append(META_END)
    return "\n".join(lines)


def extract_meta_table(body: str) -> str | None:
    start = body.find(META_START)
    end = body.find(META_END)
    if start == -1 or end == -1 or end < start:
        return None
    return body[start : end + len(META_END)]
```

- [ ] **Step 4: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "display_value or meta_table" -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/adr/lib.py scripts/adr/tests/test_lib.py
git commit -m "feat: render and extract ADR meta table (#<ISSUE#>)"
```

---

## Task 4: Discovery helpers — next_id, iter_adrs, load_tags, render_index

**Files:**
- Modify: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Write the failing tests**

Append to `scripts/adr/tests/test_lib.py`:

```python
def _make_adr(dir_: Path, num: str, name: str, status="accepted", tags=("process",)):
    fm = {
        "id": num, "name": name, "description": "d", "status": status,
        "date_proposed": "2026-06-15", "date_accepted": "2026-06-15",
        "date_invalidated": None, "author": "stimothy",
        "supersedes": [], "superseded_by": [], "tags": list(tags),
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
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "next_id or iter_adrs or load_tags or render_index" -v
```

Expected: FAIL (`AttributeError: ... 'next_id'`).

- [ ] **Step 3: Implement in `scripts/adr/lib.py`**

Add constants near the others:

```python
ADR_GLOB = "[0-9][0-9][0-9][0-9]-*.md"
ID_PREFIX_RE = re.compile(r"^(\d{4})-.*\.md$")
_TAG_ROW_RE = re.compile(r"^\|\s*(?P<tag>[^|]+?)\s*\|\s*(?P<desc>.*?)\s*\|\s*$")
```

Add functions at end of file:

```python
def next_id(adr_dir) -> str:
    nums = []
    for p in Path(adr_dir).glob(ADR_GLOB):
        m = ID_PREFIX_RE.match(p.name)
        if m:
            nums.append(int(m.group(1)))
    return f"{(max(nums) + 1) if nums else 1:04d}"


def iter_adrs(adr_dir) -> list["Adr"]:
    return [parse_adr(p) for p in sorted(Path(adr_dir).glob(ADR_GLOB))]


def load_tags(tags_path) -> dict[str, str]:
    tags: dict[str, str] = {}
    for line in Path(tags_path).read_text(encoding="utf-8").splitlines():
        m = _TAG_ROW_RE.match(line)
        if not m:
            continue
        tag = m.group("tag").strip()
        desc = m.group("desc").strip()
        if tag.lower() == "tag" or set(tag) <= {"-"}:
            continue  # header / separator rows
        tags[tag] = desc
    return tags


def render_index(adrs: list["Adr"]) -> str:
    lines = [
        "# Architecture Decision Records",
        "",
        "Generated by `./scripts/adr index` — do not edit by hand.",
        "",
        "| ID | Name | Status | Tags |",
        "| --- | --- | --- | --- |",
    ]
    for a in adrs:
        fm = a.frontmatter
        link = f"[{fm['id']}]({a.path.name})"
        lines.append(
            f"| {link} | {fm.get('name', '')} | {fm.get('status', '')} "
            f"| {display_value('tags', fm.get('tags'))} |"
        )
    return "\n".join(lines) + "\n"
```

- [ ] **Step 4: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "next_id or iter_adrs or load_tags or render_index" -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/adr/lib.py scripts/adr/tests/test_lib.py
git commit -m "feat: ADR discovery, tag registry, and index helpers (#<ISSUE#>)"
```

---

## Task 5: Validation engine (`validate_adrs`)

**Files:**
- Modify: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Write the failing tests**

Append to `scripts/adr/tests/test_lib.py`:

```python
def _registry(tmp_path: Path, *tags):
    rows = "".join(f"| {t} | desc |\n" for t in tags)
    (tmp_path / "_tags.md").write_text(
        "# ADR Tags\n\n| Tag | Description |\n| --- | --- |\n" + rows, encoding="utf-8"
    )


def test_validate_clean_repo_has_no_errors(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One", tags=("process",))
    (tmp_path / "README.md").write_text(
        lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8"
    )
    assert lib.validate_adrs(tmp_path) == []


def test_validate_detects_table_drift(tmp_path):
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8").replace("| Status | accepted |", "| Status | proposed |")
    p.write_text(text, encoding="utf-8")
    (tmp_path / "README.md").write_text(
        lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8"
    )
    errors = lib.validate_adrs(tmp_path)
    assert any("drift" in e.lower() for e in errors)


def test_validate_detects_unknown_tag(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One", tags=("ghost",))
    (tmp_path / "README.md").write_text(
        lib.render_index(lib.iter_adrs(tmp_path)), encoding="utf-8"
    )
    errors = lib.validate_adrs(tmp_path)
    assert any("ghost" in e for e in errors)


def test_validate_detects_bad_status_and_id_mismatch(tmp_path):
    _registry(tmp_path, "process")
    p = _make_adr(tmp_path, "0001", "One")
    text = p.read_text(encoding="utf-8").replace('id: "0001"', 'id: "0009"')
    p.write_text(text, encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("id" in e.lower() for e in errors)


def test_validate_detects_stale_index(tmp_path):
    _registry(tmp_path, "process")
    _make_adr(tmp_path, "0001", "One")
    (tmp_path / "README.md").write_text("# stale\n", encoding="utf-8")
    errors = lib.validate_adrs(tmp_path)
    assert any("index" in e.lower() for e in errors)
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k validate -v
```

Expected: FAIL (`AttributeError: ... 'validate_adrs'`).

- [ ] **Step 3: Implement in `scripts/adr/lib.py`**

Add at end of file:

```python
def validate_adrs(adr_dir) -> list[str]:
    """Return a list of human-readable validation errors (empty == valid)."""
    adr_dir = Path(adr_dir)
    errors: list[str] = []

    tags_path = adr_dir / "_tags.md"
    registry = load_tags(tags_path) if tags_path.exists() else {}
    if not tags_path.exists():
        errors.append("missing tag registry: _tags.md")

    for adr in iter_adrs(adr_dir):
        fm, name = adr.frontmatter, adr.path.name

        for key in FIELD_LABELS:
            if key not in fm:
                errors.append(f"{name}: frontmatter missing field '{key}'")

        status = fm.get("status")
        if status not in VALID_STATUSES:
            errors.append(f"{name}: invalid status '{status}'")

        expected_prefix = f"{fm.get('id')}-"
        if not name.startswith(expected_prefix):
            errors.append(f"{name}: filename does not match id '{fm.get('id')}'")

        table = extract_meta_table(adr.body)
        if table is None:
            errors.append(f"{name}: missing meta table markers")
        elif table != render_meta_table(fm):
            errors.append(f"{name}: meta table drift (table != frontmatter)")

        for tag in fm.get("tags") or []:
            if tag not in registry:
                errors.append(f"{name}: tag '{tag}' not in _tags.md registry")

    index_path = adr_dir / "README.md"
    expected_index = render_index(iter_adrs(adr_dir))
    if not index_path.exists():
        errors.append("missing ADR index: README.md")
    elif index_path.read_text(encoding="utf-8") != expected_index:
        errors.append("ADR index (README.md) is stale — run `./scripts/adr index`")

    return errors
```

- [ ] **Step 4: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k validate -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/adr/lib.py scripts/adr/tests/test_lib.py
git commit -m "feat: ADR validation engine (drift, schema, tags, index) (#<ISSUE#>)"
```

---

## Task 6: Mutation helpers — new_adr, accept_adr, add_tag, write_index

**Files:**
- Modify: `scripts/adr/lib.py`
- Test: `scripts/adr/tests/test_lib.py`

- [ ] **Step 1: Write the failing tests**

Append to `scripts/adr/tests/test_lib.py`:

```python
def test_new_adr_creates_valid_proposed_file(tmp_path):
    _registry(tmp_path, "process")
    path = lib.new_adr(tmp_path, name="My Choice", tags=["process"],
                       author="stimothy", today="2026-06-15")
    assert path.name == "0001-my-choice.md"
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "proposed"
    assert adr.frontmatter["date_proposed"] == "2026-06-15"
    assert adr.frontmatter["id"] == "0001"
    # table is in sync from creation
    assert lib.extract_meta_table(adr.body) == lib.render_meta_table(adr.frontmatter)


def test_accept_adr_sets_status_and_date_and_syncs_table(tmp_path):
    _registry(tmp_path, "process")
    path = lib.new_adr(tmp_path, name="My Choice", tags=["process"],
                       author="stimothy", today="2026-06-15")
    lib.accept_adr(path, today="2026-06-20")
    adr = lib.parse_adr(path)
    assert adr.frontmatter["status"] == "accepted"
    assert adr.frontmatter["date_accepted"] == "2026-06-20"
    assert lib.extract_meta_table(adr.body) == lib.render_meta_table(adr.frontmatter)


def test_add_tag_appends_and_is_idempotent(tmp_path):
    _registry(tmp_path, "process")
    lib.add_tag(tmp_path / "_tags.md", "security", "security model")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"
    lib.add_tag(tmp_path / "_tags.md", "security", "ignored second desc")
    assert lib.load_tags(tmp_path / "_tags.md")["security"] == "security model"


def test_write_index_makes_validation_pass(tmp_path):
    _registry(tmp_path, "process")
    lib.new_adr(tmp_path, name="One", tags=["process"], author="stimothy",
                today="2026-06-15")
    lib.write_index(tmp_path)
    assert lib.validate_adrs(tmp_path) == []
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "new_adr or accept_adr or add_tag or write_index" -v
```

Expected: FAIL (`AttributeError: ... 'new_adr'`).

- [ ] **Step 3: Implement in `scripts/adr/lib.py`**

Add `import datetime` to the top imports block:

```python
import datetime as _dt
```

Add at end of file:

```python
def _today_iso() -> str:
    return _dt.date.today().isoformat()


def compose_adr(fm: dict, body_sections: str) -> str:
    """Build full ADR text: frontmatter + title + meta table + sections."""
    return (
        f"---\n{dump_frontmatter(fm)}\n---\n"
        f"# ADR-{fm['id']}: {fm['name']}\n\n"
        f"{render_meta_table(fm)}\n\n"
        f"{body_sections}"
    )


_DEFAULT_SECTIONS = (
    "## Context and Problem Statement\n\n"
    "_What is the issue we are addressing? One subject only._\n\n"
    "## Decision Drivers\n\n- _driver_\n\n"
    "## Considered Options\n\n- _option_\n\n"
    "## Decision Outcome\n\n"
    "Chosen option: _option_, because _justification_.\n\n"
    "### Consequences\n\n- Good: _benefit_\n- Bad: _cost_\n\n"
    "## Links\n\n- _related ADRs / references_\n"
)


def new_adr(adr_dir, name: str, tags: list[str], author: str,
            today: str | None = None) -> "Path":
    adr_dir = Path(adr_dir)
    today = today or _today_iso()
    adr_id = next_id(adr_dir)
    fm = {
        "id": adr_id, "name": name, "description": "", "status": "proposed",
        "date_proposed": today, "date_accepted": None, "date_invalidated": None,
        "author": author, "supersedes": [], "superseded_by": [], "tags": list(tags),
    }
    path = adr_dir / f"{adr_id}-{slugify(name)}.md"
    path.write_text(compose_adr(fm, _DEFAULT_SECTIONS), encoding="utf-8")
    return path


def _rewrite_with_synced_table(adr: "Adr") -> None:
    """Persist an ADR re-rendering frontmatter + meta table, preserving sections."""
    table = extract_meta_table(adr.body)
    if table is not None:
        new_body = adr.body.replace(table, render_meta_table(adr.frontmatter), 1)
    else:
        new_body = f"{render_meta_table(adr.frontmatter)}\n\n{adr.body}"
    adr.path.write_text(
        f"---\n{dump_frontmatter(adr.frontmatter)}\n---\n{new_body}", encoding="utf-8"
    )


def accept_adr(path, today: str | None = None) -> None:
    adr = parse_adr(path)
    adr.frontmatter["status"] = "accepted"
    adr.frontmatter["date_accepted"] = today or _today_iso()
    _rewrite_with_synced_table(adr)


def add_tag(tags_path, tag: str, description: str) -> None:
    tags_path = Path(tags_path)
    if tag in load_tags(tags_path):
        return
    text = tags_path.read_text(encoding="utf-8").rstrip("\n")
    tags_path.write_text(f"{text}\n| {tag} | {description} |\n", encoding="utf-8")


def write_index(adr_dir) -> None:
    adr_dir = Path(adr_dir)
    (adr_dir / "README.md").write_text(render_index(iter_adrs(adr_dir)), encoding="utf-8")
```

Note: `parse_adr` returns `body` starting at the title line, so `accept_adr` re-renders both frontmatter and the embedded table; the title line is preserved inside `adr.body`.

- [ ] **Step 4: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_lib.py -k "new_adr or accept_adr or add_tag or write_index" -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/adr/lib.py scripts/adr/tests/test_lib.py
git commit -m "feat: ADR mutation helpers (new, accept, add-tag, index) (#<ISSUE#>)"
```

---

## Task 7: CLI (`cli.py`) + bash wrapper

**Files:**
- Create: `scripts/adr/cli.py`
- Create: `scripts/adr` (bash wrapper)
- Test: `scripts/adr/tests/test_cli.py`

- [ ] **Step 1: Write the failing tests**

`scripts/adr/tests/test_cli.py`:

```python
import textwrap
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
    rc = cli.main(["--adr-dir", str(tmp_path), "new", "--name", "First",
                   "--tags", "process", "--author", "stimothy"])
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
    cli.main(["--adr-dir", str(tmp_path), "new", "--name", "Listed",
              "--tags", "process", "--author", "stimothy"])
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
    cli.main(["--adr-dir", str(tmp_path), "new", "--name", "Searchable Thing",
              "--tags", "process", "--author", "stimothy"])
    cli.main(["--adr-dir", str(tmp_path), "find", "Searchable"])
    assert "0001" in capsys.readouterr().out
    cli.main(["--adr-dir", str(tmp_path), "show", "0001"])
    assert "Searchable Thing" in capsys.readouterr().out


def test_cli_accept(tmp_path):
    _registry(tmp_path, "process")
    cli.main(["--adr-dir", str(tmp_path), "new", "--name", "Accept Me",
              "--tags", "process", "--author", "stimothy"])
    cli.main(["--adr-dir", str(tmp_path), "accept", "0001"])
    adr = lib.parse_adr(tmp_path / "0001-accept-me.md")
    assert adr.frontmatter["status"] == "accepted"
```

- [ ] **Step 2: Run to verify failure**

```bash
.venv/bin/pytest scripts/adr/tests/test_cli.py -v
```

Expected: FAIL (`ModuleNotFoundError: No module named 'cli'`).

- [ ] **Step 3: Implement `scripts/adr/cli.py`**

```python
"""Command-line interface for ADR management. Run via ./scripts/adr."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import lib


def _find_by_id(adr_dir: Path, adr_id: str) -> lib.Adr | None:
    for adr in lib.iter_adrs(adr_dir):
        if str(adr.frontmatter.get("id")) == adr_id:
            return adr
    return None


def _cmd_new(args, adr_dir: Path) -> int:
    tags = [t.strip() for t in args.tags.split(",") if t.strip()]
    registry = lib.load_tags(adr_dir / "_tags.md")
    unknown = [t for t in tags if t not in registry]
    if unknown:
        print(f"error: unknown tag(s) {unknown}; add them first with "
              f"`./scripts/adr add-tag`", file=sys.stderr)
        return 1
    path = lib.new_adr(adr_dir, name=args.name, tags=tags, author=args.author)
    lib.write_index(adr_dir)
    print(f"created {path}")
    return 0


def _cmd_list(args, adr_dir: Path) -> int:
    for adr in lib.iter_adrs(adr_dir):
        fm = adr.frontmatter
        if args.status and fm.get("status") != args.status:
            continue
        if args.tag and args.tag not in (fm.get("tags") or []):
            continue
        print(f"{fm['id']}  {fm['status']:<10}  {fm['name']}")
    return 0


def _cmd_find(args, adr_dir: Path) -> int:
    needle = args.query.lower()
    for adr in lib.iter_adrs(adr_dir):
        text = (adr.path.read_text(encoding="utf-8")).lower()
        if needle in text:
            print(f"{adr.frontmatter['id']}  {adr.path.name}")
    return 0


def _cmd_show(args, adr_dir: Path) -> int:
    adr = _find_by_id(adr_dir, args.id)
    if not adr:
        print(f"error: no ADR with id {args.id}", file=sys.stderr)
        return 1
    print(lib.dump_frontmatter(adr.frontmatter))
    return 0


def _cmd_tags(args, adr_dir: Path) -> int:
    for tag, desc in sorted(lib.load_tags(adr_dir / "_tags.md").items()):
        print(f"{tag:<16}  {desc}")
    return 0


def _cmd_add_tag(args, adr_dir: Path) -> int:
    lib.add_tag(adr_dir / "_tags.md", args.tag, args.description)
    print(f"registered tag '{args.tag}'")
    return 0


def _cmd_tag_usage(args, adr_dir: Path) -> int:
    counts: dict[str, int] = {t: 0 for t in lib.load_tags(adr_dir / "_tags.md")}
    for adr in lib.iter_adrs(adr_dir):
        for tag in adr.frontmatter.get("tags") or []:
            counts[tag] = counts.get(tag, 0) + 1
    for tag, n in sorted(counts.items()):
        print(f"{tag:<16}  {n}")
    return 0


def _cmd_by_tag(args, adr_dir: Path) -> int:
    for adr in lib.iter_adrs(adr_dir):
        if args.tag in (adr.frontmatter.get("tags") or []):
            print(f"{adr.frontmatter['id']}  {adr.frontmatter['name']}")
    return 0


def _cmd_accept(args, adr_dir: Path) -> int:
    adr = _find_by_id(adr_dir, args.id)
    if not adr:
        print(f"error: no ADR with id {args.id}", file=sys.stderr)
        return 1
    lib.accept_adr(adr.path)
    lib.write_index(adr_dir)
    print(f"accepted {adr.path.name}")
    return 0


def _cmd_index(args, adr_dir: Path) -> int:
    lib.write_index(adr_dir)
    print("index regenerated")
    return 0


def _cmd_check(args, adr_dir: Path) -> int:
    errors = lib.validate_adrs(adr_dir)
    if errors:
        for e in errors:
            print(f"ADR CHECK: {e}", file=sys.stderr)
        return 1
    print("ADR check passed")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="adr", description="Manage ADRs.")
    p.add_argument("--adr-dir", default="docs/adrs", help="ADR directory")
    sub = p.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("new"); n.add_argument("--name", required=True)
    n.add_argument("--tags", required=True, help="comma-separated")
    n.add_argument("--author", default="stimothy"); n.set_defaults(fn=_cmd_new)

    li = sub.add_parser("list"); li.add_argument("--status"); li.add_argument("--tag")
    li.set_defaults(fn=_cmd_list)

    f = sub.add_parser("find"); f.add_argument("query"); f.set_defaults(fn=_cmd_find)
    s = sub.add_parser("show"); s.add_argument("id"); s.set_defaults(fn=_cmd_show)
    sub.add_parser("tags").set_defaults(fn=_cmd_tags)

    at = sub.add_parser("add-tag"); at.add_argument("tag")
    at.add_argument("--description", required=True); at.set_defaults(fn=_cmd_add_tag)

    sub.add_parser("tag-usage").set_defaults(fn=_cmd_tag_usage)
    bt = sub.add_parser("by-tag"); bt.add_argument("tag"); bt.set_defaults(fn=_cmd_by_tag)
    ac = sub.add_parser("accept"); ac.add_argument("id"); ac.set_defaults(fn=_cmd_accept)
    sub.add_parser("index").set_defaults(fn=_cmd_index)
    sub.add_parser("check").set_defaults(fn=_cmd_check)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.fn(args, Path(args.adr_dir))


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Create the bash wrapper `scripts/adr`**

```bash
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${HERE}/adr/cli.py" "$@"
```

Then make it executable:

```bash
chmod +x scripts/adr
```

- [ ] **Step 5: Run to verify pass**

```bash
.venv/bin/pytest scripts/adr/tests/test_cli.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/adr/cli.py scripts/adr scripts/adr/tests/test_cli.py
git commit -m "feat: ADR CLI and wrapper (#<ISSUE#>)"
```

---

## Task 8: Seed the ADR directory — template, tags, ADR-0001, index

**Files:**
- Create: `docs/adrs/_template.md`
- Create: `docs/adrs/_tags.md`
- Create: `docs/adrs/0001-record-architecture-decisions.md` (via CLI, then filled in)
- Create: `docs/adrs/README.md` (via CLI)

- [ ] **Step 1: Create `docs/adrs/_tags.md`**

```markdown
# ADR Tags

Registry of all tags used across ADRs. **Reuse an existing tag before creating a new
one.** Adding a tag to an ADR requires adding it here in the same change
(`./scripts/adr add-tag <tag> --description "..."`).

| Tag | Description |
| --- | --- |
| process | How we work; meta-process and workflow decisions |
| documentation | Documentation practices, formats, and structure |
| governance | Repo governance, ownership, and enforcement |
```

- [ ] **Step 2: Create `docs/adrs/_template.md`**

```markdown
---
id: "NNNN"
name: Short imperative decision title
description: One-line summary of the decision
status: proposed
date_proposed: YYYY-MM-DD
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags: []
---
# ADR-NNNN: Short imperative decision title

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | NNNN |
| Name | Short imperative decision title |
| Description | One-line summary of the decision |
| Status | proposed |
| Date proposed | YYYY-MM-DD |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | — |
<!-- adr-meta:end -->

## Context and Problem Statement

_What is the issue? **One subject only** — if you are deciding two things, write two ADRs._

## Decision Drivers

- _driver_

## Considered Options

- _option A_
- _option B_

## Decision Outcome

Chosen option: _X_, because _justification_.

### Consequences

- Good: _benefit_
- Bad: _cost_

## Links

- _related ADRs / references_
```

- [ ] **Step 3: Generate ADR-0001 via the CLI**

```bash
./scripts/adr new --name "Record architecture decisions using MADR with frontmatter" \
  --tags process,documentation,governance --author stimothy
```

This creates `docs/adrs/0001-record-architecture-decisions-using-madr-with-frontmatter.md`. Rename to the canonical short name:

```bash
git -C . mv \
  "docs/adrs/0001-record-architecture-decisions-using-madr-with-frontmatter.md" \
  "docs/adrs/0001-record-architecture-decisions.md"
```

- [ ] **Step 4: Fill in ADR-0001 content**

Open `docs/adrs/0001-record-architecture-decisions.md`. Set `description` in **both** the frontmatter and the meta table to: `Defines the ADR process: MADR format, dual frontmatter+table representation, lifecycle, one-subject rule, and tooling.` Then replace the body sections (everything from `## Context and Problem Statement` onward) with:

```markdown
## Context and Problem Statement

This is a live, public service where `master` deploys to production. We need durable,
reviewable records of architecturally significant decisions so the codebase cannot
silently drift from agreed direction, and so AI agents and humans share one source of
truth for "why".

## Decision Drivers

- Decisions must be machine-parseable (for AI agents) and human-readable.
- Records must not silently drift between machine and human representations.
- Changing one decision must not require invalidating unrelated ones.
- Finding/creating ADRs must be cheap (token-efficient) for AI agents.

## Considered Options

- MADR with YAML frontmatter + a mirrored human table (chosen).
- Nygard-style plain Markdown (no structured metadata).
- An external decision-log tool / database.

## Decision Outcome

Chosen option: **MADR with YAML frontmatter plus a rendered human metadata table**,
managed by the `./scripts/adr` CLI, because it satisfies both machine and human
readers and lets tooling enforce consistency.

Rules established by this ADR:

1. **Location & naming.** ADRs live in `docs/adrs/` as `NNNN-kebab-title.md`
   (zero-padded id). `_template.md` is the template; `_tags.md` is the tag registry;
   `README.md` is the generated index.
2. **Format.** MADR sections. YAML frontmatter is the **source of truth**; the table
   between `<!-- adr-meta:start -->`/`<!-- adr-meta:end -->` is rendered from it. The
   `check` command fails on any drift.
3. **Frontmatter schema.** `id, name, description, status, date_proposed,
   date_accepted, date_invalidated, author, supersedes, superseded_by, tags`.
4. **One subject per ADR.** Each ADR records exactly one decision, so a future change
   supersedes a single ADR rather than invalidating unrelated ones.
5. **Lifecycle.** `proposed → accepted → superseded | deprecated`, plus `rejected`.
   ADRs are created `proposed` and stay so until the user accepts them. **An ADR is
   authoritative only once `accepted` AND merged to `master`.**
6. **Sequencing (hybrid by risk).** Foundational/high-risk decisions land as their own
   ADR PR first; lower-risk ADRs travel with their implementing code.
7. **Required when** a change touches: a new framework/major dependency, a public API
   contract, the persistence/data model, the auth/security model, build/deploy
   topology, a cross-cutting convention, or repo-wide tooling.
8. **Tags.** Reuse an existing tag from `_tags.md` before creating a new one; new tags
   must be registered in `_tags.md` in the same change.
9. **The law.** Once accepted on `master`, code may not contradict an ADR without a new
   ADR that supersedes it (the old one flips to `superseded` with cross-links).
10. **Tooling.** All ADR operations go through `./scripts/adr` (see `--help`); the
    `adr-create`, `adr-search`, and `adr-tags` Claude skills wrap it.

### Consequences

- Good: single source of truth; enforced consistency; cheap lookups; atomic decisions.
- Bad: small ceremony per decision; contributors must run `./scripts/adr` rather than
  hand-editing metadata.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
- MADR: https://adr.github.io/madr/
```

- [ ] **Step 5: Accept ADR-0001, regenerate index, and validate**

```bash
./scripts/adr accept 0001
./scripts/adr index
./scripts/adr check
```

Expected: `accepted 0001-...`, `index regenerated`, `ADR check passed`.

- [ ] **Step 6: Commit**

```bash
git add docs/adrs/
git commit -m "docs: add ADR-0001 defining the ADR process (#<ISSUE#>)"
```

---

## Task 9: Coverage gate green

**Files:**
- Possibly modify: `scripts/adr/tests/test_lib.py` (add tests for any uncovered branches)

- [ ] **Step 1: Run full suite with coverage**

```bash
.venv/bin/pytest --cov-fail-under=80
```

Expected: all tests PASS and `Required test coverage of 80% reached`. If coverage <80%, the run fails and the missing lines are listed by `term-missing`.

- [ ] **Step 2: Add targeted tests for any uncovered lines**

For each uncovered line reported, add a focused test in `scripts/adr/tests/test_lib.py` (or `test_cli.py`) exercising that branch. Example pattern for an error branch:

```python
def test_show_missing_id_errors(tmp_path):
    _registry(tmp_path, "process")
    import cli
    assert cli.main(["--adr-dir", str(tmp_path), "show", "9999"]) == 1
```

Re-run `.venv/bin/pytest` until coverage ≥ 80%.

- [ ] **Step 3: Lint**

```bash
.venv/bin/ruff check scripts/adr
.venv/bin/ruff format --check scripts/adr
```

Expected: no errors. If `ruff format --check` reports diffs, run `.venv/bin/ruff format scripts/adr` and re-run tests.

- [ ] **Step 4: Commit**

```bash
git add scripts/adr/
git commit -m "test: reach coverage gate and pass lint for ADR tooling (#<ISSUE#>)"
```

---

## Task 10: Claude skills

**Files:**
- Create: `.claude/skills/adr-create/SKILL.md`
- Create: `.claude/skills/adr-search/SKILL.md`
- Create: `.claude/skills/adr-tags/SKILL.md`

- [ ] **Step 1: Create `.claude/skills/adr-search/SKILL.md`**

```markdown
---
name: adr-search
description: Use FIRST when you need to find an existing ADR or check whether a decision already exists, before reading ADR files manually — saves tokens by querying the CLI.
---

# Searching ADRs

Always use the CLI instead of reading `docs/adrs/` files directly. It is far cheaper.

- List all ADRs: `./scripts/adr list` (filter: `--status accepted`, `--tag security`)
- Full-text search: `./scripts/adr find "<query>"`
- Inspect one ADR's metadata: `./scripts/adr show <id>` (e.g. `0001`)

Only open the full ADR file once `find`/`show` has pinpointed the right id and you need
the prose. To understand the ADR process itself, read ADR-0001.
```

- [ ] **Step 2: Create `.claude/skills/adr-create/SKILL.md`**

```markdown
---
name: adr-create
description: Use when recording a new architecturally significant decision (new framework/major dependency, public API contract, persistence or auth model, build/deploy topology, cross-cutting convention, or repo-wide tooling).
---

# Creating an ADR

First confirm one is needed (see ADR-0001's trigger checklist) and that no ADR already
covers it (`./scripts/adr find "<topic>"`).

1. Pick tags. Reuse existing ones: `./scripts/adr tags`. If a new tag is truly needed,
   register it first: `./scripts/adr add-tag <tag> --description "..."`.
2. Create the ADR (status `proposed`): `./scripts/adr new --name "<imperative title>"
   --tags a,b --author stimothy`. **One subject per ADR.**
3. Fill in the MADR sections in the generated file. Do NOT hand-edit the frontmatter
   block or the meta table values inconsistently — keep frontmatter authoritative; if
   you change frontmatter, run `./scripts/adr index` and `./scripts/adr check`.
4. Leave status `proposed`. The user accepts it (`./scripts/adr accept <id>`); it is law
   only once accepted AND merged to master.
5. Regenerate the index and validate: `./scripts/adr index && ./scripts/adr check`.
```

- [ ] **Step 3: Create `.claude/skills/adr-tags/SKILL.md`**

```markdown
---
name: adr-tags
description: Use when choosing, reviewing, or adding ADR tags — to reuse existing tags before inventing new ones and keep the _tags.md registry consistent.
---

# ADR Tags

The tag registry is `docs/adrs/_tags.md`. Every tag used by any ADR must exist there.

- List tags + descriptions: `./scripts/adr tags`
- See usage counts: `./scripts/adr tag-usage`
- Find ADRs by tag: `./scripts/adr by-tag <tag>`
- Register a new tag (only if no existing tag fits): `./scripts/adr add-tag <tag>
  --description "..."`

Prefer reusing an existing tag. After changes, run `./scripts/adr check`.
```

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/
git commit -m "feat: add adr-create, adr-search, adr-tags Claude skills (#<ISSUE#>)"
```

---

## Task 11: CLAUDE.md + README.md skeletons

**Files:**
- Create/Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Write `CLAUDE.md`**

```markdown
# CLAUDE.md — Zarlania API

AI entry point for this repo. **This is a live, public service: merges to `master`
deploy to production at https://api.zarlania.com.** Work carefully.

## Non-negotiables

- **Never commit secrets.** No credentials, tokens, or keys in any commit. Secrets live
  only in Render environment variables and local `.env` (git-ignored).
- **ADRs are law.** Code may not contradict an accepted ADR without a new ADR that
  supersedes it. See `docs/adrs/0001-record-architecture-decisions.md`.
- **Every change ties to a GitHub issue.** Branch `type/<issue#>-slug`; PR title
  references `#<issue>`.

## Working with ADRs (save tokens)

To find an ADR or check whether a decision exists, **use the `adr-search` skill / the
CLI — do not scan `docs/adrs/` by hand**:

- `./scripts/adr list` / `./scripts/adr find "<query>"` / `./scripts/adr show <id>`

To create one, use the `adr-create` skill. For tags, use `adr-tags`. Run
`./scripts/adr check` after any ADR change.

## Status

Phase 1 (ADR foundation) is in place. Quality gates, CI, the app shell, and deployment
config arrive in later phases (see `docs/superpowers/specs/`).
```

- [ ] **Step 2: Write `README.md`**

```markdown
# Zarlania API

Backend service for Zarlania, deployed at https://api.zarlania.com. Java 25 / Spring
Boot / Maven, containerized and hosted on Render.

> Early scaffolding stage — see `docs/superpowers/specs/` for the design and
> `docs/superpowers/plans/` for implementation plans.

## Architecture Decision Records

Significant decisions are recorded as ADRs in `docs/adrs/`. Browse them with the CLI:

```bash
./scripts/adr list            # list all ADRs
./scripts/adr find "<query>"  # search ADRs
./scripts/adr show 0001       # show one ADR's metadata
./scripts/adr check           # validate ADRs (no drift, valid tags, fresh index)
```

The ADR process itself is defined in
`docs/adrs/0001-record-architecture-decisions.md`.

## Developer setup (Phase 1)

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest        # run ADR tooling tests
```
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: add CLAUDE.md and README skeletons (#<ISSUE#>)"
```

---

## Task 12: Open the PR

- [ ] **Step 1: Push the branch**

```bash
git push -u origin "feat/<ISSUE#>-adr-foundation"
```

- [ ] **Step 2: Open the PR referencing the issue**

```bash
gh pr create \
  --title "Phase 1: ADR foundation (#<ISSUE#>)" \
  --body "$(cat <<'EOF'
Implements Phase 1 of the repo-shell spec: ADR storage layout, the ./scripts/adr CLI
(create/list/find/show/tags/add-tag/tag-usage/by-tag/accept/index/check) with drift +
schema + tag + index validation, pytest suite (≥80% branch coverage) + ruff, the
adr-create/adr-search/adr-tags Claude skills, ADR-0001 defining the ADR process, and
CLAUDE.md/README skeletons.

Closes #<ISSUE#>

Spec: docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md (§2, §3)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Verify locally before requesting review**

```bash
.venv/bin/pytest && .venv/bin/ruff check scripts/adr && ./scripts/adr check
```

Expected: tests pass, lint clean, `ADR check passed`.

---

## Out of scope for Phase 1 (later phases)

- Maven quality plugins, `.pre-commit-config.yaml`, `setup-dev`/`check` scripts (Phase 2).
- CI workflows, CODEOWNERS, templates, Dependabot, OSS health files (Phase 3).
- Actuator/springdoc/CORS app shell, render.yaml, docker-compose, `bump-version` (Phase 4).
- Seed ADRs 0002–0006 (Phase 5).
- Wiring `./scripts/adr check` into pre-commit/CI happens in Phases 2–3; for now it is
  run manually.
```
