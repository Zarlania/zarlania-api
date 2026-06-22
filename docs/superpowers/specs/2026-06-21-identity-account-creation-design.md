# Identity domain: account-creation orchestration — design

- **Date:** 2026-06-21
- **Status:** Approved (pending implementation)
- **Scope:** Slice 1 of the `identity` domain — the public API to create an account
  (a user plus their personal organization), created active, with no authentication,
  passwords, login, email verification, or rate limiting.

## Summary

Add an `identity` domain that orchestrates the existing `users` and `organizations`
domains to create an account in a single atomic operation: a user (email + username) and
their `PERSONAL` organization named after the username. Expose it as one HTTP endpoint,
`POST /accounts`. This is the first HTTP controller and first write endpoint in the
codebase, so it also introduces the first global exception handler that maps domain
exceptions to HTTP responses.

The reference doc `docs/reference/000001-user-organization-association-rules.md` already
reserves this orchestration for a future `identity` domain ("account-creation
orchestration that creates a user and their personal org atomically"). This spec builds
exactly that first slice.

## Goals

- Create a user and their personal organization atomically via one public API call.
- Name the personal organization after the user's unique `username`, so the global
  organization-name uniqueness constraint also DB-backs the one-personal-org-per-user rule
  (per reference doc 000001).
- Map domain exceptions to correct HTTP status codes via a global handler returning
  RFC 7807 `ProblemDetail` responses.

## Non-goals (deferred to later slices)

- **Authentication / authorization / Spring Security** — no login, no passwords, no
  protected routes. The endpoint is public. (Future Slice: authentication.)
- **Email verification** — accounts are created active. The `PENDING → ACTIVE` lifecycle,
  an email provider, verification tokens, and a verify endpoint are a separate subsystem.
  (Future Slice: email verification.)
- **Abuse prevention** — no per-IP rate limiting or CAPTCHA in this slice; it pairs with
  the public-launch story, which is deferred.
- **Read/update/delete account endpoints** — create-only (YAGNI). No `GET`.
- **API versioning** — bare paths; introduce versioning only when a breaking change forces
  it (its own decision then).

## Architecture

A dedicated `identity` domain, consistent with CLAUDE.md's feature-first layout and
ADR-0011 (decoupled domains, DTO boundaries, in-process bean calls).

```
com.zarlania.api.identity
  controller/  IdentityController     — POST /accounts -> 201 Account
  service/     IdentityService        — @Transactional orchestration
  dto/         Account                — { user, personalOrganization }
               CreateAccountRequest   — { email, username } inbound payload
                                        (carries Jakarta Validation annotations)

com.zarlania.api.web                  — new shared web-layer package
  ApiExceptionHandler                 — first global @RestControllerAdvice
```

`IdentityService` injects `UserService` and `OrganizationService` as Spring beans and
exchanges only DTOs — it never imports or holds either domain's JPA entity. This honors
ADR-0011 rule 1 (entities private to their domain) and rule 5 (cross-domain interaction is
an in-process service-bean call).

The `ApiExceptionHandler` lives in a new `com.zarlania.api.web` package rather than inside
`identity`, because it maps exceptions originating in *multiple* domains (users and
organizations) and is web-layer cross-cutting infrastructure, not identity-specific.
Catching another domain's *exception* types is permitted under ADR-0011 — that rule forbids
importing another domain's *entities*, not its exceptions or DTOs.

## API contract

### Request

```
POST /accounts
Content-Type: application/json

{ "email": "user@example.com", "username": "someuser" }
```

Modeled as `CreateAccountRequest(String email, String username)`.

### Success — 201 Created

```json
{
  "user": { "id": "<uuid>", "email": "user@example.com", "username": "someuser" },
  "personalOrganization": { "id": "<uuid>", "name": "someuser", "type": "PERSONAL" }
}
```

Modeled as `Account(User user, Organization personalOrganization)`, reusing the existing
`User` and `Organization` DTOs. The DTO carries the canonical domain name (`Account`) per
CLAUDE.md.

### Errors — RFC 7807 `ProblemDetail`

| Exception (source domain) | HTTP | Cause |
| --- | --- | --- |
| `MethodArgumentNotValidException` (bean validation) | `400` | request field fails `@NotBlank`/`@Size`/`@Email` at the HTTP edge |
| `IllegalArgumentException` (users/orgs services) | `400` | blank or too-long email/username that bypasses bean validation (defensive) |
| `EmailAlreadyExistsException` (users) | `409` | email already registered |
| `UsernameAlreadyExistsException` (users) | `409` | username already taken |
| `OrganizationNameAlreadyExistsException` (orgs) | `409` | chosen username collides with an existing *general* org name; surfaced as "username unavailable" |
| `PersonalOrganizationAlreadyExistsException` (orgs) | `409` | cannot occur for a brand-new user; mapped defensively |
| any other exception | `500` | unexpected failure |

The `OrganizationNameAlreadyExistsException` case is a genuine edge: personal-org names are
globally unique across *all* organizations, so a previously created general (company) org
named, say, `someuser` makes the username `someuser` unusable for a personal org. From the
caller's perspective the username is unavailable, so it returns `409`.

## Orchestration flow & atomicity

`IdentityService.createAccount(String email, String username)` — annotated
`@Transactional`:

1. `User user = userService.create(email, username)`
2. `Organization org = organizationService.createPersonalOrganization(user.id(), user.username())`
3. `return new Account(user, org)`

Both `UserService.create` and `OrganizationService.createPersonalOrganization` are
`@Transactional` with the default `REQUIRED` propagation, so they **join** the outer
transaction opened by `IdentityService`. If step 2 throws (for example, an org-name
collision surfaced as `OrganizationNameAlreadyExistsException`), the user insert from step 1
is rolled back as part of the same transaction — no orphaned user is left behind. This is
the atomic "user + personal org" guarantee the reference doc reserved for `identity`.

The inner services already convert race-condition `DataIntegrityViolationException`s into
their domain exceptions (via DB unique-constraint detection), so `identity` adds no new
persistence logic — it only orchestrates and propagates.

## Validation

Two complementary layers at two distinct boundaries — defense in depth, not duplication:

1. **HTTP edge — Jakarta Bean Validation on `CreateAccountRequest`.** Add
   `spring-boot-starter-validation` and annotate the request record: `@NotBlank`/`@Size(max
   = 320)`/`@Email` on `email`, `@NotBlank`/`@Size(max = 100)` on `username`. The
   controller marks the body `@Valid`; a failure yields `MethodArgumentNotValidException`,
   which `ApiExceptionHandler` maps to `400` with per-field detail. This is the primary,
   future-proof validation path and additionally enforces email *format* (`@Email`), which
   the services do not.
2. **Domain invariant — existing service-layer guards.** `UserService.create` and
   `OrganizationService.createPersonalOrganization` keep their null/blank/length checks.
   These are *retained*, because they are domain invariants that protect the `users` and
   `organizations` domains for **every** caller (not just `identity`) and are covered by
   those domains' own behavior tests. Removing them to satisfy DRY would weaken those
   domains and break their tests. `ApiExceptionHandler` still maps their
   `IllegalArgumentException` to `400` as a defensive fallback.

The only genuine duplication is the max-length constants (320 / 100) appearing both as bean
annotations and as `UserService` constants. This is accepted: the alternative — exposing
`users`-domain internals so `identity` could reference them — would violate the domain
boundary (ADR-0011). `spring-boot-starter-validation` is a standard Spring starter, not an
architecturally significant dependency, so it needs no ADR.

## Testing (TDD — behavior through the public surface)

Write tests first; assert observable behavior, not mock interactions or internals.

**`IdentityService` integration test** (real `UserService` + `OrganizationService` against
H2):

- Creating an account persists a user and a `PERSONAL` organization named after the
  username, with a single `OWNER` membership; the returned `Account` carries both DTOs.
- **Atomicity:** when the username equals an existing general org's name, account creation
  fails and *no* user is persisted (verify the user cannot be found by email afterward).
- Duplicate email and duplicate username each raise the mapped domain exception.

**`IdentityController` web test** (MockMvc):

- `POST /accounts` with a valid body returns `201` and the expected JSON shape.
- Field-level validation: blank email/username, an over-length value, and a malformed
  email each return `400` with per-field `ProblemDetail` detail (bean validation).
- Each domain error path (duplicate email, duplicate username, name collision) returns a
  `ProblemDetail` with the correct HTTP status.

The build's ≥ 80% coverage gate applies; tests must prove behavior, not just cover lines.

## Cross-cutting concerns

- **Dependency:** add `spring-boot-starter-validation` (standard Spring starter; not
  ADR-worthy). First adoption of Jakarta Bean Validation in the codebase, establishing the
  convention that request DTOs are validated at the controller boundary with `@Valid`.
- **OpenAPI (ADR-0003):** springdoc auto-documents `POST /accounts` at `/v3/api-docs` and
  the Swagger UI. Optional `@Operation`/schema annotations may be added for clarity; not
  required.
- **CORS (ADR-0004):** the endpoint is subject to the existing CORS allowlist; no change.
- **Release (CLAUDE.md):** feature change → `release:minor` label; bump `pom.xml`
  `0.4.1 → 0.5.0` inside the PR.
- **Workflow (ADR-0008):** ties to a new GitHub issue; branch
  `feat/<issue#>-identity-account-creation`; PR title references the issue.

## Documentation impact

This change alters documented system behavior, so the relevant **reference docs** must be
updated as part of the same change (reference docs describe *how the system behaves*; they
do **not** document API endpoints — OpenAPI at `/v3/api-docs` is the source of truth for
the endpoint contract).

- **Update reference doc 000001 (User–organization association rules):**
  - The "other half" of the personal-org rule now lands: account creation always creates a
    user's personal org, so the rule moves from "**at most one**" to "**exactly one**
    personal org per user." Update the wording that currently attributes this to a "future
    account-creation orchestration."
  - The personal org **is** now named after the owner's `username` (no longer "intended"
    / "via the future orchestration").
  - The Scope note that defers "account-creation orchestration that creates a user and
    their personal org atomically" to a "future `identity` domain" must be revised — that
    orchestration now exists in `com.zarlania.api.identity`. (Cross-domain existence
    checks, permission gates, and authentication/secrets remain deferred.)
  - Add `com.zarlania.api.identity` to the doc's Related list.

- **Update CLAUDE.md (Working with reference docs):** add explicit guidance that when a
  change alters documented behavior, the contributor updates the relevant reference doc (or
  authors a new one) **as part of that change**, and that reference docs document
  behavior/rules — **not** API endpoints, which OpenAPI owns. (CLAUDE.md today says
  reference docs are "updated in place" and to "author … as the situation warrants," but
  does not state the keep-in-sync obligation or the no-endpoints boundary.) This is
  operational guidance elaborating ADR-0013; it is not itself an ADR.

Both documentation updates are deliverables of this change and are committed on the feature
branch alongside the code — not to `master` directly.

## Open questions

None outstanding. Authentication, email verification, abuse prevention, and read/update
endpoints are intentionally deferred to later slices (see Non-goals).

## Links

- Reference doc 000001 — User–organization association rules (reserves account-creation
  orchestration for `identity`).
- ADR-0011 — Keep domains decoupled in code with DB-level integrity.
- ADR-0003 — Serve API docs via public springdoc OpenAPI.
- ADR-0008 — Require issue-driven contribution workflow.
- ADR-0009 — Release every merge via in-PR SemVer bump.
