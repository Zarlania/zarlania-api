# Design: users, personal organizations, and core authentication

- **Issue:** [#26](https://github.com/Zarlania/zarlania-api/issues/26)
- **Date:** 2026-07-25
- **Applies to:** `Zarlania/zarlania-api` only.
- **Spec chain:** 2 of 7. Predecessor:
  [spec 1 — persistence foundation and domain package convention](2026-07-25-persistence-foundation-design.md)
  (the full seven-part decomposition is listed there). Successor:
  [spec 3 — general organizations, roles, and permissions](2026-07-25-general-orgs-roles-permissions-design.md).

## Purpose

Give Zarlania real accounts: registration with a blocking email-verification
loop, login with Argon2-checked passwords, short-lived org-scoped JWT access
tokens, and rotating refresh-token families with reuse detection. Every user
gets a personal organization; all tokens are scoped to exactly one user and
one organization. Built production-grade — this goes live when it merges.

## Scope

Delivered: the `users`, `organizations`, `credentials`, and `auth` domains
(personal orgs only), `common.email`, migrations for all six tables, the
deferred `BaseEntity` + its convention test from spec 1, Spring Security
resource-server configuration, and endpoints:

| Endpoint | Purpose |
| -------- | ------- |
| `POST /auth/register` | Create account + personal org, send verification |
| `POST /auth/verify` | Consume emailed token, mark email verified |
| `POST /auth/verify/resend` | Fresh verification token |
| `POST /auth/login` | Credentials → access JWT + refresh cookie |
| `POST /auth/refresh` | Rotate refresh token, new access JWT |
| `POST /auth/logout` | Revoke refresh family, clear cookie |
| `GET /.well-known/jwks.json` | Public signing keys |
| `GET /users/me` | Current user + personal-org DTO |

Out of scope (later specs): general organizations, org switching, roles and
permissions claims (3); impersonation, system tokens, admin roles, super-admin
bootstrap (4); all frontend (5–7); OAuth social login (future — but the design
keeps the seam: an OAuth callback would authenticate the identity, then reuse
the same token-issuance path login uses).

## Security architecture (chosen approach)

**Spring Security as resource server + our own auth endpoints.** Spring
Security's `oauth2-resource-server` validates JWTs on every request against
our JWKS; our plain controllers own register/login/refresh/verify and mint
tokens with the Nimbus JOSE library Spring ships; passwords hash with
Spring Security's `Argon2PasswordEncoder`.

Rejected: **Spring Authorization Server** (built for third-party redirect
flows; OAuth 2.1 has no password grant, so a first-party SPA login fights it,
and org-scoped claims would mean deep customization — revisit only if Zarlania
becomes an IdP) and **hand-rolled filters** (owning hardened security plumbing
that already exists).

## Domain map

Per spec 1's convention — sub-package layers, entities never leave their
domain, cross-domain references are FK ids + DTO lookups:

- **`users`** — `User`: `email`, `username` (both unique, case-insensitive via
  `citext`), `email_verified_at` (null until verified). No password material,
  no tokens.
- **`organizations`** — `Organization`: `name` (unique, `citext`), `type`
  (`PERSONAL` | `GENERAL`); `Membership`: `organization` mapped in-domain,
  `user_id` plain FK id, `is_owner`. Spec 2 only creates personal orgs
  (name = username, sole owner-member = the user). Spec 3 extends this domain.
- **`credentials`** — `PasswordCredential` (`user_id` FK id, Argon2 hash) and
  `EmailVerificationToken` (`user_id` FK id, SHA-256 token hash, expiry,
  consumed-at). Proofs of identity, quarantined from the user record.
- **`auth`** — auth endpoints, token minting, JWKS, `RefreshToken`
  (family id, `user_id` + `organization_id` FK ids, SHA-256 token hash,
  expiry, used-at, revoked-at). Registration is orchestrated here: one
  service, one transaction, composing users/credentials/organizations through
  their services and DTOs.
- **`common.email`** — `EmailSender` interface + provider adapter. No
  entities; domain-agnostic infrastructure beside `common.persistence`.

**Migrations** create `users`, `organizations`, `organization_memberships`,
`password_credentials`, `email_verification_tokens`, `refresh_tokens` on
spec 1's conventions: `uuid` pk, `timestamptz(6)` audit columns, real FK
constraints, `citext` for case-insensitive uniques.

## Registration and email verification

`POST /auth/register` — email, username, password.

- **Validation:** email format; username 3–30 chars `[a-z0-9-]` (it becomes
  the personal-org name, so URL/display-safe); password 12–128 chars, no
  composition rules (length beats forced symbols — NIST).
- **One transaction:** unverified `User` + `PasswordCredential` + personal
  `Organization` + owner `Membership`. The verification email sends only
  after commit — never for a rolled-back registration.
- **Verification token:** 256-bit random URL-safe, only its SHA-256 hash
  stored, 24 h TTL, single-use. Link lands on the app
  (`zarlania.com/verify-email?token=…`) which calls `POST /auth/verify`;
  success stamps `email_verified_at` and invalidates outstanding tokens.
- **Blocking:** unverified login fails with distinct code
  `auth.email-unverified`; the app offers `POST /auth/verify/resend`
  (invalidates prior tokens).
- **Enumeration hygiene:** usernames are public — taken username is an honest
  `409`. Emails are not — registering an in-use email returns the same
  `202 "check your email"` as success, and the existing owner receives a
  "someone tried to register with your email" notice instead. Login failures
  never reveal which of identifier/password was wrong.
- **Unverified expiry:** accounts unverified after 7 days are purged by a
  scheduled cleanup (email/username free again). Re-registering an unverified
  email before then re-sends verification; it never alters credentials.
- **Provider: Resend** (free tier ~3k emails/month) behind `EmailSender` —
  plug-and-play: a provider swap is a new adapter. Manual steps for the
  maintainer: create the Resend account, add its SPF/DKIM records to
  `zarlania.com` DNS, set the API key env var on Render.

## Login, access tokens, refresh families

`POST /auth/login` — identifier (email **or** username) + password. Success
mints for the user's **personal org** (org switching is spec 3): access JWT in
the JSON body; refresh token as an httpOnly cookie (`Secure`,
`SameSite=Strict`, `Path=/auth`). The SPA holds the access token in memory
only.

- **Access JWT** (15 min TTL): header `kid`; claims `iss`
  (`https://api.zarlania.com`), `sub` (user uuid), `org` (organization uuid),
  `kind` (`user` — the discriminator spec 4's impersonation/system tokens
  reuse), `iat`, `exp`, `jti`. Deliberately minimal; display data comes from
  `GET /users/me`; a roles claim is spec 3's business.
- **Refresh tokens are opaque** (256-bit random, SHA-256 hash stored), not
  JWTs — they are validated against the database by design.
- **Families:** created at login with an **absolute 30-day lifetime**;
  refreshing rotates the token but never extends the family — a stolen cookie
  dies within 30 days regardless; monthly re-login is the accepted cost.
- **`POST /auth/refresh`:** cookie in → mark used, issue the family's next
  token (rotated cookie) + fresh access JWT, re-checking the user still
  exists and is verified. **Reuse of an already-used token is a theft
  signal: the entire family is revoked** and re-login is required.
- **`POST /auth/logout`:** revokes the family, clears the cookie; in-flight
  access tokens simply expire (stateless by design; 15-minute exposure
  accepted).

## Cryptography and key management

- **Passwords: Argon2id** (`Argon2PasswordEncoder`), OWASP baseline —
  19 MiB, 2 iterations, parallelism 1 — fine inside Render's 512 MB.
  Parameters are self-describing in the hash; future strengthening rehashes
  on next successful login.
- **JWT signing: RS256 (RSA-2048)** — universally supported by JWKS
  consumers; `kid` on every token means even an algorithm migration is just a
  rotation.
- **Keys:** private key PEM in `JWT_PRIVATE_KEY` (documented `openssl`
  one-liner; manual step to set it on Render; never in git). JWKS serves the
  public half; `kid` = RFC 7638 thumbprint. Rotation is config-only: new
  private key in, old public key in `JWT_RETIRED_PUBLIC_KEYS` until the
  15-minute horizon passes, then dropped.
- **Dev/prod split:** local profile with no key generates an ephemeral dev
  keypair at startup (zero setup); production with no key fails startup
  (spec 1's fail-fast posture).
- **Stored-token rule:** anything bearer-shaped that we persist — refresh and
  verification tokens — is stored only as a SHA-256 hash.

## Security configuration

- **Filter chain:** stateless resource-server. Public: `/auth/**`,
  `/.well-known/jwks.json`, `/actuator/health`. Everything else needs a valid
  access JWT; a custom converter yields one typed principal carrying user id +
  org id — no claim parsing in controllers or services.
- **CSRF:** disabled for the bearer API; the one cookie-reading endpoint
  (`/auth/refresh`) is defended by `SameSite=Strict` + `Path=/auth` + CORS
  locked to the app origin — no CSRF-token machinery in a stateless API.
- **CORS:** origins from configuration (prod `https://zarlania.com`, dev list
  via env), `Allow-Credentials: true`, tight method/header lists.
- **Throttling:** per-IP and per-account rate limits with `429` on login,
  register, resend, and refresh. **In-memory** buckets (e.g. Bucket4j local):
  Render free tier is exactly one instance, so distributed state buys nothing
  today. The limiter sits behind an owned interface; if the app ever scales
  out, that interface's Redis adapter is Redis's first consumer (per spec 1's
  deferral).
- **No account lockout** on top of throttling — lockout is a trivial DoS
  against a known username; Argon2 cost + rate limits are the defense.
- Actuator stays health-only; TLS/HSTS terminate at Render.

## Error handling

Every error is an RFC 9457 `ProblemDetail` with a stable machine-readable
code (`auth.email-unverified`, `auth.username-taken`, …) so the app branches
on codes, never prose. Field-level detail on validation failures; uniform 401
on bad login; enumeration-safe responses as above; no internals in any 5xx.

## Testing

A single injectable `Clock` bean everywhere time matters — every expiry test
is deterministic, no sleeps.

- **Unit:** registration orchestration, family rotation/reuse-revocation,
  validators, claim building — services with mocked ports.
- **Integration (Testcontainers Postgres):** spec 1's boot smoke now proves
  the real migrations; the deferred `BaseEntity` convention test (UUID
  assigned on save, `timestamptz(6)` microsecond timestamps, `updated_at`
  moves while `created_at` doesn't) asserts against the real `users` table;
  repository slices via `@DataJpaTest`.
- **End-to-end (full-context MockMvc):** register → capture token from a
  recording `EmailSender` test double (CI never sends real email) → verify →
  login → refresh → **reuse detected, family revoked** → logout; plus
  unverified-login rejection, throttle `429`s, JWKS shape, 401 without token.
- **Coverage:** the 80% JaCoCo gate stands.

## Configuration added

`RESEND_API_KEY`, `JWT_PRIVATE_KEY`, `JWT_RETIRED_PUBLIC_KEYS` (optional),
CORS origin list, token TTLs / family lifetime / throttle limits as
`application.yml` values (env-overridable per spec 1). All secrets are
Render-side env vars; `.env.example` documents local knobs.

## Decisions log

| Decision | Choice | Alternatives rejected |
| -------- | ------ | --------------------- |
| Security skeleton | Resource server + own endpoints | Spring Authorization Server; hand-rolled filters |
| Email verification | Real provider, blocking login | Non-blocking; stubbed sender |
| Email provider | Resend free tier, behind `EmailSender` | Brevo et al. (adapter swap anytime) |
| JWT signing | RS256 + JWKS, `kid` rotation | HS256 shared secret; Ed25519 (less universal) |
| Login identifier | Email or username | Email-only; username-only |
| Refresh transport | httpOnly `SameSite=Strict` cookie | JSON body + localStorage (XSS-stealable) |
| Refresh lifetime | Absolute 30-day family, rotation per use | Sliding expiry (indefinite stolen-cookie life) |
| Reuse response | Revoke whole family | Revoke single token only |
| Brute force | In-memory per-IP/account throttle | Redis-backed (no consumer need at 1 instance); account lockout (DoS vector) |
| Password policy | 12–128 chars, no composition rules | Symbol/case composition requirements |
