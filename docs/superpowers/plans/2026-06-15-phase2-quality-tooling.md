# Phase 2 — Quality & Security Tooling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the local quality & security gates — Maven-driven Spotless/Checkstyle/SpotBugs+FindSecBugs/JaCoCo (run by `./mvnw verify`) plus a `.pre-commit-config.yaml` of fast cross-language checks and the `setup-dev`/`check` developer scripts.

**Architecture:** Heavy analysis (formatting, style, bug/security analysis, coverage) is configured as Maven plugins bound to the `verify` phase, so a single `./mvnw verify` enforces everything and CI (Phase 3) can call it. Fast checks (format/style for Java via one `mvnw` invocation, secrets, markdown/yaml/shell/python lint, ADR validation, file hygiene) run pre-commit via the `pre-commit` framework, which manages its own hook tool environments. Two bash glue scripts give humans/agents one command to set up (`setup-dev`) and one to run the fast suite (`check`).

**Tech Stack:** Java 25 / Maven (wrapper), Spotless (google-java-format), Checkstyle (Google config), SpotBugs + FindSecBugs, JaCoCo, `pre-commit` framework, gitleaks, markdownlint, shellcheck/shfmt, ruff, yamllint, Bash.

**Process:** Built on a feature branch off `master`, tied to a GitHub issue, merged via PR (governance is live as of Phase 1). The branch must end with `./mvnw verify` and `pre-commit run --all-files` both green.

**Spec:** `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` (§4, §10, §13 Phase 2).

---

## Important context for the implementer

- **Toolchain present locally:** Temurin JDK 25.0.3 (`JAVA_HOME` set), Maven via `./mvnw` (3.9.16), `shellcheck`, `markdownlint-cli2`. The Python venv from Phase 1 is at `.venv/`. `pre-commit`, `shfmt`, `yamllint` are NOT installed locally but the `pre-commit` framework installs hook tools itself; `setup-dev` installs `pre-commit`.
- **Existing Java is the POC code** (`src/main/java/com/zarlania/api/{ZarlaniaApiApplication,HelloController,WebConfig}.java` + 3 tests under `src/test/...`). The real app shell (actuator/springdoc/CORS) is Phase 4. **Phase 2 must make this existing POC code pass all the new gates** (reformat with Spotless, satisfy Checkstyle/SpotBugs, meet coverage). It will be replaced in Phase 4, but the gates must be green now.
- **JDK 25 tooling risk:** the pinned plugin/formatter versions below are the intended starting point. If a goal fails specifically due to JDK 25 incompatibility (e.g., class-file version, google-java-format needing `--add-exports`), bump to the latest available version of that plugin and, if needed, add the required `--add-exports` JVM args — then note the change in the commit message. Do not silently disable a gate to get green.
- **Bats deliberately deferred:** `setup-dev`/`check` are thin glue; they're covered by shellcheck+shfmt lint and a manual smoke run. A bats unit-test harness is deferred until a bash script carries real logic (the logic-heavy ADR tooling is Python, pytest-covered). This is a conscious deviation from spec §4.2's "bats" mention.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | Add Spotless, Checkstyle, SpotBugs(+FindSecBugs), JaCoCo plugins, bound to `verify`; coverage rule + excludes. |
| `config/checkstyle/checkstyle-suppressions.xml` | Targeted Checkstyle suppressions (e.g., generated/boilerplate) to reconcile with google-java-format. |
| `config/spotbugs/spotbugs-exclude.xml` | SpotBugs/FindSecBugs exclude filter for unavoidable false positives. |
| `.pre-commit-config.yaml` | Fast pre-commit hooks (Java via mvnw, secrets, md/yaml/sh/py lint, ADR check, hygiene). |
| `.markdownlint.yaml` | Markdown lint rules (relax line-length etc.) so existing docs pass. |
| `.yamllint.yaml` | YAML lint rules (relax line-length, document-start). |
| `requirements-dev.txt` | Add `pre-commit`. |
| `scripts/setup-dev` | Bash: create venv, install dev deps + pre-commit, install git hooks, sanity-check toolchain. |
| `scripts/check` | Bash: run the fast local check suite (`pre-commit run --all-files`), with `--full` to also run `./mvnw verify`. |
| existing `src/**/*.java` | Reformatted/adjusted to pass the gates. |

---

## Task 0: Issue + branch

- [ ] **Step 1: Create the GitHub issue**

```bash
gh issue create \
  --title "Phase 2: Quality & security tooling (Maven gates + pre-commit + dev scripts)" \
  --body "Implements Phase 2 of the repo-shell spec (docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md §4,§10): Spotless/Checkstyle/SpotBugs+FindSecBugs/JaCoCo via ./mvnw verify, .pre-commit-config.yaml fast checks, and scripts/setup-dev + scripts/check. Existing POC code is brought up to the gates (real app shell is Phase 4)."
```

Note the issue number (referred to below as `<ISSUE#>`).

- [ ] **Step 2: Create the branch**

```bash
git switch -c "feat/<ISSUE#>-quality-tooling"
```

---

## Task 1: Spotless (google-java-format)

**Files:** Modify `pom.xml`; reformat `src/**/*.java`.

- [ ] **Step 1: Add the Spotless plugin to `pom.xml`** inside `<build><plugins>` (after the existing `spring-boot-maven-plugin`):

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.44.5</version>
  <configuration>
    <java>
      <googleJavaFormat>
        <version>1.25.2</version>
      </googleJavaFormat>
      <removeUnusedImports/>
      <importOrder/>
      <trimTrailingWhitespace/>
      <endWithNewline/>
    </java>
  </configuration>
  <executions>
    <execution>
      <id>spotless-check</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Verify the check fails on unformatted POC code (expected)**

Run: `./mvnw -q spotless:check`
Expected: either PASS (already formatted) or FAIL listing files needing formatting. If it errors with a JDK-25/`--add-exports` message instead, bump `googleJavaFormat` to the latest version and retry (note the change).

- [ ] **Step 3: Apply formatting**

Run: `./mvnw -q spotless:apply`
This rewrites `src/**/*.java` to google-java-format.

- [ ] **Step 4: Verify check now passes**

Run: `./mvnw -q spotless:check`
Expected: PASS (no output / build success).

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/
git commit -m "build: add Spotless google-java-format and format existing code (#<ISSUE#>)"
```

---

## Task 2: Checkstyle (Google config)

**Files:** Modify `pom.xml`; create `config/checkstyle/checkstyle-suppressions.xml`; possibly adjust `src/**/*.java`.

- [ ] **Step 1: Create `config/checkstyle/checkstyle-suppressions.xml`**

```xml
<?xml version="1.0"?>
<!DOCTYPE suppressions PUBLIC
    "-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN"
    "https://checkstyle.org/dtds/suppressions_1_2.dtd">
<suppressions>
  <!-- Spring Boot main class: framework-shaped, exempt from Javadoc/HideUtilityCtor noise. -->
  <suppress files="ZarlaniaApiApplication\.java" checks="HideUtilityClassConstructor|MissingJavadocType"/>
</suppressions>
```

- [ ] **Step 2: Add the Checkstyle plugin to `pom.xml`** (inside `<build><plugins>`):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.6.0</version>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>10.21.1</version>
    </dependency>
  </dependencies>
  <configuration>
    <configLocation>google_checks.xml</configLocation>
    <suppressionsLocation>config/checkstyle/checkstyle-suppressions.xml</suppressionsLocation>
    <includeTestSourceDirectory>true</includeTestSourceDirectory>
    <consoleOutput>true</consoleOutput>
    <failOnViolation>true</failOnViolation>
    <violationSeverity>warning</violationSeverity>
  </configuration>
  <executions>
    <execution>
      <id>checkstyle-check</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 3: Run Checkstyle and reconcile with google-java-format**

Run: `./mvnw -q checkstyle:check`
Expected: ideally PASS. google_checks.xml is designed to align with google-java-format, but a few rules (e.g., `JavadocPackage`, `SummaryJavadoc`, `AbbreviationAsWordInName`) may flag the POC code. For each violation, prefer fixing the code (e.g., add a `package-info.java` or a class Javadoc) if it is a reasonable standard; if a rule is pure noise for this codebase, add a narrow `<suppress>` entry in `checkstyle-suppressions.xml` (do not broadly disable). Re-run until PASS.

> If Checkstyle requires package Javadoc, create `src/main/java/com/zarlania/api/package-info.java`:
> ```java
> /** Zarlania API service. */
> package com.zarlania.api;
> ```

- [ ] **Step 4: Commit**

```bash
git add pom.xml config/checkstyle/ src/
git commit -m "build: add Checkstyle (Google config) and satisfy it (#<ISSUE#>)"
```

---

## Task 3: SpotBugs + FindSecBugs

**Files:** Modify `pom.xml`; create `config/spotbugs/spotbugs-exclude.xml`.

- [ ] **Step 1: Create `config/spotbugs/spotbugs-exclude.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<FindBugsFilter>
  <!-- Exclude the generated/framework Spring Boot bootstrap class. -->
  <Match>
    <Class name="com.zarlania.api.ZarlaniaApiApplication"/>
  </Match>
</FindBugsFilter>
```

- [ ] **Step 2: Add the SpotBugs plugin to `pom.xml`** (inside `<build><plugins>`):

```xml
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <version>4.9.3.0</version>
  <configuration>
    <effort>Max</effort>
    <threshold>Low</threshold>
    <includeTests>true</includeTests>
    <excludeFilterFile>config/spotbugs/spotbugs-exclude.xml</excludeFilterFile>
    <plugins>
      <plugin>
        <groupId>com.h3xstream.findsecbugs</groupId>
        <artifactId>findsecbugs-plugin</artifactId>
        <version>1.14.0</version>
      </plugin>
    </plugins>
  </configuration>
  <executions>
    <execution>
      <id>spotbugs-check</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 3: Run SpotBugs**

SpotBugs analyzes compiled bytecode, so compile first:
Run: `./mvnw -q clean compile test-compile spotbugs:check`
Expected: PASS. If it reports real issues in POC code, fix them. If it reports a defensible false positive, add a narrow `<Match>` to the exclude filter. If it fails to run on JDK 25 bytecode, bump `spotbugs-maven-plugin` to the latest version (FindSecBugs runs as its plugin) and retry; note the change.

- [ ] **Step 4: Commit**

```bash
git add pom.xml config/spotbugs/
git commit -m "build: add SpotBugs + FindSecBugs security analysis (#<ISSUE#>)"
```

---

## Task 4: JaCoCo coverage gate (line + branch ≥ 80%)

**Files:** Modify `pom.xml`; possibly add tests under `src/test/...`.

- [ ] **Step 1: Add the JaCoCo plugin to `pom.xml`** (inside `<build><plugins>`):

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.13</version>
  <configuration>
    <excludes>
      <exclude>com/zarlania/api/ZarlaniaApiApplication.class</exclude>
    </excludes>
  </configuration>
  <executions>
    <execution>
      <id>jacoco-prepare</id>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>jacoco-report</id>
      <phase>verify</phase>
      <goals><goal>report</goal></goals>
    </execution>
    <execution>
      <id>jacoco-check</id>
      <phase>verify</phase>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum>
              </limit>
              <limit>
                <counter>BRANCH</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.80</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Run coverage**

Run: `./mvnw -q clean verify`
Expected: tests run, JaCoCo report generated at `target/site/jacoco/`, and the coverage check passes. If JaCoCo reports below 80% line or branch (the POC `HelloController`/`WebConfig` may have uncovered branches), add focused tests under `src/test/java/com/zarlania/api/` to cover the gap. Example — a test asserting the hello endpoint body if missing:

```java
// in HelloControllerTest.java (only if a coverage gap exists there)
@org.junit.jupiter.api.Test
void helloReturnsExpectedMessage() throws Exception {
  mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
}
```

Re-run `./mvnw -q clean verify` until the coverage check passes. If JaCoCo 0.8.13 cannot instrument JDK 25 classes, bump to the latest JaCoCo version and retry (note the change).

- [ ] **Step 3: Commit**

```bash
git add pom.xml src/
git commit -m "build: add JaCoCo line+branch coverage gate at 80% (#<ISSUE#>)"
```

---

## Task 5: Full `./mvnw verify` integration checkpoint

- [ ] **Step 1: Run the full verify**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS with Spotless check, Checkstyle, SpotBugs+FindSecBugs, tests, and JaCoCo all passing. If anything fails, fix it (see the relevant task above) before proceeding. Do not weaken a gate to pass.

- [ ] **Step 2: No commit** (verification only; nothing changed if green).

---

## Task 6: Pre-commit configuration (fast checks)

**Files:** Create `.pre-commit-config.yaml`, `.markdownlint.yaml`, `.yamllint.yaml`; modify `requirements-dev.txt`.

- [ ] **Step 1: Add `pre-commit` to `requirements-dev.txt`** (append the line):

```text
pre-commit==4.0.1
```

- [ ] **Step 2: Create `.markdownlint.yaml`**

```yaml
# markdownlint config — relax rules that conflict with our doc style.
default: true
MD013: false   # line length: prose/tables wrap naturally
MD024:
  siblings_only: true   # allow repeated headings under different parents
MD033: false   # inline HTML (used for ADR meta-table markers/comments)
MD041: false   # first line need not be a top-level heading
```

- [ ] **Step 3: Create `.yamllint.yaml`**

```yaml
extends: default
rules:
  line-length: disable
  document-start: disable
  truthy:
    check-keys: false   # allow GitHub Actions "on:" etc. in later phases
  comments:
    min-spaces-from-content: 1
```

- [ ] **Step 4: Create `.pre-commit-config.yaml`**

```yaml
# Fast pre-commit checks. Heavy analysis (SpotBugs/JaCoCo/tests) runs in CI via ./mvnw verify.
minimum_pre_commit_version: "3.5.0"
repos:
  - repo: https://github.com/pre-commit/pre-commit-hooks
    rev: v5.0.0
    hooks:
      - id: trailing-whitespace
      - id: end-of-file-fixer
      - id: check-yaml
      - id: check-added-large-files
      - id: check-merge-conflict
      - id: detect-private-key

  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.21.2
    hooks:
      - id: gitleaks

  - repo: https://github.com/astral-sh/ruff-pre-commit
    rev: v0.9.2
    hooks:
      - id: ruff
        args: [--fix]
      - id: ruff-format

  - repo: https://github.com/igorshubovych/markdownlint-cli
    rev: v0.43.0
    hooks:
      - id: markdownlint
        args: ["--config", ".markdownlint.yaml"]

  - repo: https://github.com/scop/pre-commit-shfmt
    rev: v3.10.0-2
    hooks:
      - id: shfmt
        args: ["-i", "2", "-ci", "-d"]

  - repo: https://github.com/shellcheck-py/shellcheck-py
    rev: v0.10.0.1
    hooks:
      - id: shellcheck

  - repo: https://github.com/adrienverge/yamllint
    rev: v1.35.1
    hooks:
      - id: yamllint
        args: ["-c", ".yamllint.yaml"]

  - repo: local
    hooks:
      - id: java-quality
        name: Spotless + Checkstyle (Java, via mvnw)
        entry: ./mvnw -q spotless:check checkstyle:check
        language: system
        types: [java]
        pass_filenames: false
      - id: adr-check
        name: ADR validation (./scripts/adr check)
        entry: ./scripts/adr check
        language: system
        files: ^docs/adrs/
        pass_filenames: false
```

> Pin notes: `ruff` rev must match `requirements-dev.txt` (v0.9.2). If a hook `rev` is yanked/unavailable at install time, move it to the latest tag and note it.

- [ ] **Step 5: Commit**

```bash
git add .pre-commit-config.yaml .markdownlint.yaml .yamllint.yaml requirements-dev.txt
git commit -m "build: add pre-commit fast-check configuration (#<ISSUE#>)"
```

---

## Task 7: `scripts/setup-dev`

**Files:** Create `scripts/setup-dev`.

- [ ] **Step 1: Create `scripts/setup-dev`**

```bash
#!/usr/bin/env bash
# Set up the local dev environment: Python venv, dev deps, and git pre-commit hooks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

echo "==> Creating Python venv (.venv) if missing"
if [[ ! -d .venv ]]; then
  python3 -m venv .venv
fi

echo "==> Installing dev dependencies"
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet -r requirements-dev.txt

echo "==> Installing git hooks (pre-commit)"
.venv/bin/pre-commit install

echo "==> Verifying Java toolchain"
if ! ./mvnw -v >/dev/null 2>&1; then
  echo "WARNING: ./mvnw not runnable — a JDK is required for Java quality hooks." >&2
fi

echo "==> Done. Run ./scripts/check to run the fast checks."
```

- [ ] **Step 2: Make it executable and shellcheck-clean**

```bash
chmod +x scripts/setup-dev
shellcheck scripts/setup-dev
```
Expected: no shellcheck output (clean).

- [ ] **Step 3: Smoke-test it**

Run: `./scripts/setup-dev`
Expected: venv ready, deps installed, `pre-commit installed at .git/hooks/pre-commit`, "Done" message.

- [ ] **Step 4: Commit**

```bash
git add scripts/setup-dev
git commit -m "feat: add scripts/setup-dev for one-command dev setup (#<ISSUE#>)"
```

---

## Task 8: `scripts/check`

**Files:** Create `scripts/check`.

- [ ] **Step 1: Create `scripts/check`**

```bash
#!/usr/bin/env bash
# Run the fast local check suite (mirrors pre-commit). Use --full to also run mvn verify.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

FULL=0
if [[ "${1:-}" == "--full" ]]; then
  FULL=1
fi

PRE_COMMIT=".venv/bin/pre-commit"
if [[ ! -x "${PRE_COMMIT}" ]]; then
  PRE_COMMIT="pre-commit"
fi

echo "==> Running fast checks (pre-commit on all files)"
"${PRE_COMMIT}" run --all-files

if [[ "${FULL}" -eq 1 ]]; then
  echo "==> Running full Maven verify (tests + SpotBugs + JaCoCo)"
  ./mvnw clean verify
fi

echo "==> Checks passed."
```

- [ ] **Step 2: Make it executable and shellcheck-clean**

```bash
chmod +x scripts/check
shellcheck scripts/check
```
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add scripts/check
git commit -m "feat: add scripts/check to run the fast local check suite (#<ISSUE#>)"
```

---

## Task 9: Green the whole fast suite

**Files:** Possibly touch existing markdown/yaml/shell files flagged by linters.

- [ ] **Step 1: Run the fast suite across the repo**

Run: `./scripts/check`
(equivalently `.venv/bin/pre-commit run --all-files`)
Expected: every hook passes. The first run installs hook environments (slow once).

- [ ] **Step 2: Fix what the linters flag**

For each failing hook, fix the underlying files (the hygiene hooks auto-fix trailing whitespace / EOF; markdownlint/yamllint/shellcheck/shfmt require manual fixes or config tuning in `.markdownlint.yaml` / `.yamllint.yaml`). Existing files in scope: `README.md`, `CLAUDE.md`, `docs/**/*.md`, `docs/adrs/**`, `scripts/adr`, the new scripts, and any YAML. Re-run until all hooks pass. Do not disable a hook wholesale to get green; tune narrowly.

- [ ] **Step 3: Confirm the Java hook + ADR hook work**

Run: `.venv/bin/pre-commit run java-quality --all-files` and `.venv/bin/pre-commit run adr-check --all-files`
Expected: both PASS (java-quality runs `./mvnw spotless:check checkstyle:check`; adr-check runs `./scripts/adr check`).

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "chore: satisfy pre-commit linters across existing files (#<ISSUE#>)"
```

---

## Task 10: Final verification + PR

- [ ] **Step 1: Run both gates end to end**

```bash
./scripts/check --full
```
Expected: `pre-commit run --all-files` all green, then `./mvnw clean verify` BUILD SUCCESS (Spotless, Checkstyle, SpotBugs+FindSecBugs, tests, JaCoCo ≥80% line+branch). Also confirm `./scripts/adr check` still passes (it runs inside pre-commit).

- [ ] **Step 2: Push the branch**

```bash
git push -u origin "feat/<ISSUE#>-quality-tooling"
```

- [ ] **Step 3: Open the PR**

```bash
gh pr create \
  --title "Phase 2: Quality & security tooling (#<ISSUE#>)" \
  --body "$(cat <<'EOF'
Implements Phase 2 of the repo-shell spec (§4, §10).

## What's included
- **Maven gates (run by `./mvnw verify`):** Spotless (google-java-format), Checkstyle (Google config + narrow suppressions), SpotBugs + FindSecBugs (effort=Max), JaCoCo (line+branch ≥80%, Application class excluded).
- **`.pre-commit-config.yaml`:** fast checks — Java format/style via mvnw, gitleaks, ruff, markdownlint, shellcheck+shfmt, yamllint, file-hygiene hooks, and `./scripts/adr check`.
- **`scripts/setup-dev`** (venv + deps + git hooks) and **`scripts/check`** (`--full` also runs `./mvnw verify`).
- Existing POC Java reformatted/adjusted to pass all gates (real app shell is Phase 4).

## Deviations
- **Bats deferred:** the only bash scripts are thin glue (shellcheck/shfmt-linted); a bats harness waits for logic-heavy bash. (Spec §4.2 mentioned bats.)
- Any plugin/formatter version bumps made for JDK 25 compatibility are noted in their commits.

## Verification
- `./scripts/check --full` → pre-commit all green; `./mvnw clean verify` BUILD SUCCESS.

Closes #<ISSUE#>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Out of scope (later phases)

- GitHub Actions CI running these gates + coverage PR comment, branch protection, CODEOWNERS, issue/PR templates, Dependabot, OSS health files (Phase 3).
- App shell: actuator/springdoc/CORS, render.yaml, docker-compose, `bump-version`, release automation (Phase 4 / Phase 6 ADR).
- Seed ADRs 0002–0006 (Phase 5).
