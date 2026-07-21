# Contributing to zarlania-api

Thanks for your interest in contributing. This document describes how to get set
up and the rules that CI enforces.

By participating you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

## The one rule that surprises people

**Every change must be tracked by an issue, and the issue number must appear in
both the branch name and the pull request title.** The `PR Lint` workflow fails
the build otherwise. This keeps the history traceable back to a stated reason for
each change.

| Thing        | Format                                | Example                     |
| ------------ | ------------------------------------- | --------------------------- |
| Branch       | `<issue-number>-<slug>`               | `42-add-hello-endpoint`     |
| PR title     | `#<issue-number> <type>: <desc>`      | `#42 feat: add hello endpoint` |
| PR body      | must contain `Closes #<issue-number>` | `Closes #42`                |

Allowed types: `feat`, `fix`, `chore`, `docs`, `refactor`, `perf`, `test`,
`build`, `ci`, `style`, `revert`.

The issue must exist and be open, and all three references must point at the same
issue number.

> Tip: on any issue page, use **Create a branch** in the sidebar. GitHub generates
> a correctly formatted branch name for you.

## Workflow

1. **Find or open an issue.** Use the [issue templates](https://github.com/Zarlania/zarlania-api/issues/new/choose)
   — bug report, feature request, or chore. For open-ended ideas, start a
   [discussion](https://github.com/Zarlania/zarlania-api/discussions) instead.
2. **Branch off `master`**, naming the branch after the issue.
   ```bash
   git switch master && git pull
   git switch -c 42-add-hello-endpoint
   ```
3. **Make the change**, with tests.
4. **Verify locally** — see below.
5. **Open a pull request** against `master` using the required title format.
6. **Apply a version label** — `major`, `minor` or `patch`. This determines the
   version number of the release your merge produces. Without one it defaults to
   a patch bump.
7. **Address review feedback.** Once approved and green, a maintainer merges.

Direct pushes to `master` are not part of the workflow — all changes go through a
pull request.

## Local setup

Requires JDK 25 (Temurin recommended). Maven comes from the wrapper.

```bash
git clone https://github.com/Zarlania/zarlania-api.git
cd zarlania-api
./mvnw verify
```

## Before you push

```bash
./mvnw spotless:apply   # reformat to Google Java Style
./mvnw verify           # compile, test, check formatting and coverage
```

`./mvnw verify` is what CI runs. If it passes locally it should pass in CI.

To install the pre-commit hook (formats staged Java files and scans for secrets):

```bash
git config core.hooksPath .githooks
```

## Testing and coverage

Tests use JUnit 5 with Spring Boot test slices. **Coverage must stay at or above
80%** for both lines and branches, enforced by JaCoCo during `./mvnw verify`.
Every pull request gets a coverage comment.

The HTML report lands at `target/site/jacoco/index.html` after a build.

Optionally scan for secrets the same way CI does:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 \
  detect --source=/repo --config=/repo/.gitleaks.toml --redact --verbose
```

## Code style

Java is formatted with [Google Java Style](https://google.github.io/styleguide/javaguide.html),
enforced by [Spotless](https://github.com/diffplug/spotless). Formatting is not a
matter of preference here — `./mvnw verify` fails on any deviation, and
`./mvnw spotless:apply` fixes it. There is no need to discuss formatting in review.

Beyond formatting, this project holds itself to a few principles — the full
version, with examples, is in [CLAUDE.md](CLAUDE.md).

**Legibility.** Code should be understandable in isolation. Names state intent, no
magic values, comments explain *why* rather than *what*, classes and methods stay
small, and types are precise — records for data carriers, `Optional` over `null`.

**DRY.** Extract duplicated logic into a well-named method or class. But wait for
the third occurrence: a wrong abstraction is more expensive to unwind than a
little duplication.

**SOLID:**

- *Single responsibility* — controllers handle HTTP only; business rules live in
  services, persistence behind repositories.
- *Open/closed* — extend by adding an implementation, not another branch to an
  existing `switch`.
- *Liskov substitution* — implementations honour their interface's contract.
- *Interface segregation* — interfaces stay narrow and role-based.
- *Dependency inversion* — depend on interfaces, injected through the
  constructor. Never field injection.

Also:

- Name tests for the behaviour they assert, not the method they call.
- Prefer test slices (`@WebMvcTest`) over `@SpringBootTest` when the full context
  is not needed — they are dramatically faster.

## Commit messages

Individual commits are not linted — the pull request title is what matters, since
pull requests are squash-merged and the title becomes the commit message and the
release-note entry.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).

## Questions

Open a [discussion](https://github.com/Zarlania/zarlania-api/discussions) or see
[SUPPORT.md](SUPPORT.md).
