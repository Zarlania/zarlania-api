---
id: '000004'
title: Request throttling
description: How the @Throttled aspect, its per-IP and per-account buckets, the configured
  endpoint limits, and the client-IP source that keys them work.
tags:
- architecture
- configuration
- http
- security
created: '2026-08-09'
updated: '2026-08-14'
related:
- '000002'
- '000003'
---

# Request throttling

<!-- reference-table:start -->
| Field | Value |
| ----- | ----- |
| ID | 000004 |
| Title | Request throttling |
| Description | How the @Throttled aspect, its per-IP and per-account buckets, the configured endpoint limits, and the client-IP source that keys them work. |
| Tags | architecture, configuration, http, security |
| Created | 2026-08-09 |
| Updated | 2026-08-14 |
| Related | [000002](000002-authentication-and-tokens.md), [000003](000003-outbound-email.md) |
<!-- reference-table:end -->

How this service bounds how often a caller may hit an endpoint: the `throttle`
topic package, plus `http`'s `ClientIpResolver`, which exists to answer the one
question the per-IP bucket depends on.

Every throttled endpoint today is an `/auth` route, so the limits below are what
stands in front of registration, login and verification —
[Authentication and tokens](000002-authentication-and-tokens.md) covers what
those endpoints do and the other defenses around them. Outbound email carries a
separate, service-wide budget spent through this same limiter; [Outbound
email](000003-outbound-email.md) owns it.

## How a limit is declared

Throttling is declarative. A handler carries
`@Throttled(endpoint = "login", accountFrom = "identifier")` and holds no
throttling code of its own; `ThrottleAspect` enforces it, counting against
`InMemoryRateLimiter`, a fixed-window limiter behind the `RateLimiter`
interface.

It is an **aspect** rather than a servlet filter or a `HandlerInterceptor`, the
two obvious homes for middleware, because the per-account bucket keys on a field
of the *parsed request body*. A filter and an interceptor both run before
argument resolution, so neither can reach that field without buffering and
re-parsing the body; advice on the handler method runs after binding. One
mechanism therefore carries both bucket kinds instead of splitting them across
two places.

Being infrastructure in the request path rather than a service, the aspect
raises `ApiException` directly — the case `CLAUDE.md` reserves it for, so a
refusal renders through the same `ProblemDetails` envelope a handler-mapped
failure does.

## Two buckets per request

A throttled request that names an account consumes **two** buckets —
`<endpoint>:<client-ip>` and `<endpoint>:acct:<identifier>` — because either
alone leaves a real attack unbounded. With only the per-IP bucket, credential
stuffing spread across many addresses can hammer one known username freely;
with only the per-account bucket, a single address can work through a list of
accounts.

The per-IP bucket is consumed first, and a refusal there means the per-account
bucket is never touched. An endpoint whose annotation names no `accountFrom`
stops after the first bucket.

`ThrottleKeys` trims and lower-cases the account half of the key, because
`email` and `username` are `citext`: Postgres already treats `Bob` and `bob`
as one account, so keying on the raw string would hand out a fresh allowance
per spelling. The key is length-capped as well, since `LoginRequest.identifier`
is only `@NotBlank` and could otherwise mint arbitrarily long keys in the
limiter's map.

Two things the per-account limit is deliberately **not**:

- It is not an **account lockout**, which was rejected outright: locking an
  account after N failures is a trivial denial-of-service against a known
  username. This window is a minute wide and refills itself, so a sustained
  attack can suppress one account's logins only while it is actually
  running.
- It is not an **enumeration channel**. The bucket exists for whatever
  string the caller supplied, whether or not an account matches it, so a
  `429` says an identifier has been tried a lot — never that it is real.

### Two further buckets, deliberately not built yet

Every throttled endpoint today is public, so the only identities available are
the caller's address and whatever account the request body names — and the
second only approximates an account, since it keys on a string the caller typed
rather than on a row that exists. An authenticated endpoint carries better
identities, already proved: the access token's `sub` is the user and its `org`
is the organization. So the first throttled authenticated endpoint should bring
a **per-user** bucket keyed on `sub`, bounding one account across every address
it calls from, and a **per-organization** bucket keyed on `org`, so a shared
organization gets one budget rather than one per member — which only starts to
matter once general organizations exist.

Neither is added in advance, because a limit no endpoint consumes is a number
nobody can tell is wrong. What would have to give when they arrive is the
assumption that `accountLimit` is the *only* optional bucket, which is what lets
`EndpointLimits.accountLimitIfPresent()` serve today as the general test for
"does this endpoint have a second bucket".

## Configured limits

Limits live in `zarlania.throttle.endpoints`, keyed by the name in the
annotation, and are all counted over `zarlania.throttle.window` (1 minute).
They are a map rather than one field per endpoint, so throttling a new route is
an annotation plus a configuration entry and never a change to
`ThrottleProperties`:

| Endpoint | Per client IP | Per account |
| -------- | ------------- | ----------- |
| `register` | 5/min | 3/min |
| `login` | 10/min | 10/min |
| `resend` | 3/min | 3/min |
| `verify` | 10/min | — (the token *is* the request) |
| `refresh` | 30/min | — (names no account) |
| `logout` | 60/min | — (names no account) |
| `csrf` | 60/min | — (names no account) |

Every `/auth` route carries a limit, including the four that name no account.
`verify` and `logout` need one precisely because neither demands a credential
the caller cannot mint for itself: without a limit, `verify` lets an
unauthenticated caller guess verification tokens at a token-table lookup each,
and `logout` lets one [CSRF
pair](000002-authentication-and-tokens.md#csrf-protection-is-scoped-not-disabled)
— fetched freely from `GET /auth/csrf`, and good for any number of requests — be
replayed against invented refresh cookies, a refresh-token lookup each.

`login`'s per-account limit matches its per-IP limit rather than sitting below
it. A real person never makes ten login attempts in a minute, and going lower
would only make it cheaper to suppress one account's logins.

`refresh` stays per-IP only at 30/min. It carries an opaque 256-bit cookie
rather than an identifier, so there is no account to key on and nothing to
brute force; the limit is a flood cap. A client needs roughly 4 refreshes an
hour (the 15-minute [access-token
TTL](000002-authentication-and-tokens.md#access-token-jwt)), so 30/min still
covers several hundred users sharing one NAT or CGNAT address.

`logout` and `csrf` sit above `refresh` for the same kind of reason: neither may
be what refuses a legitimate client. A client may log out on every tab it has
open, and being throttled out of logging out is the one refusal that leaves a
session alive against the user's wishes. A client has to fetch a CSRF token
before it can refresh at all, so that limit must never be what refuses a
legitimate refresh. `csrf` protects nothing expensive in any case — no database
work, no hashing, just a random token — so its limit is there for uniformity
across the public `/auth` routes.

## What a refused caller sees

A caller over either limit gets `429` with code `throttle.limit-exceeded`,
carrying a `Retry-After` header. The limiter returns the remaining window
alongside the refusal rather than through a second lookup, so the advertised
wait always describes the window that actually rejected the request. The value
is whole seconds per RFC 9110 §10.2.3, rounded up and never below one, so a
client that obeys it to the letter arrives after the window has genuinely
refilled instead of retrying into a second rejection.

The code is prefixed `throttle` even though every endpoint answering with it
today sits under `/auth`. `CLAUDE.md`'s rule is that a code's prefix names the
enum that publishes it rather than the callers that happen to use it, and
`ThrottleErrorCode` is domain-agnostic: a throttled endpoint outside `auth`
answers with the same code.

`Retry-After` is not one of the CORS-safelisted response headers, so
`SecurityConfig`'s CORS configuration names it in `setExposedHeaders`. Without
that, the browser client receives the `429` with the header stripped and cannot
implement the back-off above. `setAllowedHeaders` does not cover it: that
governs what a request may *send*, not what script may *read* off a response.

## Keeping the annotation and the configuration in step

Splitting a limit across an annotation and a configuration entry means either
half can go missing without anything noticing until a request arrives, so
`ThrottledEndpointConventionTest` checks the two against each other at build
time, reading the real `application.yml` and scanning the real controllers.

Part of that drift does fail loudly: an endpoint absent from configuration
(`ThrottleProperties.limitsFor`), an `accountFrom` naming a component no
argument declares (`AccountIdentifierReader`), and an `accountFrom` with no
matching `account-limit` (`ThrottleAspect`) each throw `IllegalStateException`
rather than let a bucket quietly not apply. Loud, but at request time — which
makes the first person to find out a caller in production. The rest never
announce themselves at all:

- An `account-limit` with nothing to spend it — no `accountFrom` on the
  annotation — leaves the per-account bucket **off**.
- An `accountFrom` naming a component that is not a `String` still produces a
  key, because the value is read through `Objects.toString`. The bucket then
  counts a `toString()` of some other shape, and the only symptom is a limit
  that never bites. The annotation is a bare string and cannot express the
  constraint, so this test is the only place it can be held.
- A configured endpoint that no handler claims is a limit nothing applies,
  which reads to whoever tunes it next as a limit that is in force.

## The limiter is in memory, and swept

`InMemoryRateLimiter` holds one `RateLimitWindow` per key in a
`ConcurrentHashMap`. It is in-memory rather than Redis-backed because Render's
free plan runs exactly one instance — distributed state would buy nothing
today. `RateLimiter` is a narrow, owned interface for exactly this reason: a
Redis-backed implementation can drop in behind it without any caller changing,
the day the service ever runs on more than one instance.

Left alone that map grows for the life of the process, since every distinct key
that has ever made a request stays resident. `evictExpiredWindows` sweeps it on
a `@Scheduled` fixed delay of one window rather than scanning on every
`tryConsume` call, which would trade the leak for a latency hit on the auth hot
path. That sweep is one of the three `@Scheduled` methods
`spring.task.scheduling.pool.size` has to cover — see [Scheduled
cleanup](000002-authentication-and-tokens.md#scheduled-cleanup), whose two
sweeps occupy the other two threads at the same time.

How much the map is holding is published as a Micrometer gauge,
`zarlania.throttle.tracked.keys`. Unbounded growth is the failure mode the
class is built around and nothing else the service reports would reveal it, so
it is measured rather than assumed. `trackedKeyCount()` deliberately sits on the
implementation and not on `RateLimiter`: it describes how this implementation
stores its state, and a Redis-backed one would have nothing to report.

The counter increment runs outside the map's `compute` call. `compute`'s
per-key lock only guarantees that one `RateLimitWindow` is published per key; it
orders nothing against the sweep removing that same entry. The count itself is
an `AtomicInteger`, so callers racing on the same live window can neither lose
nor double-count an increment. The one gap is a request landing between
`compute` returning a window and the sweep dropping it, which discounts at most
one increment against a key nobody will read again — an acceptable
approximation for a limiter, not a hole a caller could work.

## Where the client IP comes from

The per-IP bucket is only as good as the address it keys on. That address comes
from the `ClientIpResolver` port, whose one implementation
`CloudflareClientIpResolver` reads `CF-Connecting-IP` — not `X-Forwarded-For`,
and not `getRemoteAddr()`. Reading the wrong one silently disables every limit
above, in one of two directions: forgeable, or one bucket for the entire
service.

**The deployed chain has two appending hops, not one.** Render fronts
every service with Cloudflare as well as its own load balancer:

```text
client ──▶ Cloudflare edge ──▶ Render load balancer ──▶ this app

X-Forwarded-For: 208.54.226.138, 172.69.40.233, 10.24.118.242
                 ^ real client    ^ Cloudflare    ^ Render LB
CF-Connecting-IP: 208.54.226.138
```

Three plausible sources, two of which are actively wrong:

- `getRemoteAddr()` is the TCP peer — Render's load balancer. Identical
  for every request from every user, so it collapses each endpoint into
  one global bucket, capping the whole service at, say, 5 registrations a
  minute *total* rather than 5 per caller.
- The **leftmost** `X-Forwarded-For` entry is whatever the client sent: a
  proxy appends rather than replaces. Rotating it buys a fresh bucket per
  request — unlimited login brute force, registration email-bombing and
  resend. This is also what `server.forward-headers-strategy: framework`
  uses (`ForwardedHeaderUtils.parseForwardedFor` takes index `[0]`, and
  prefers a client-supplied `Forwarded:` header outright), which is why
  that setting must stay `none` — see below.
- The **rightmost** entry is the Render load balancer's private `10.x`
  address — byte-identical across separate probes of a live service.
  Unforgeable, and exactly as useless as `getRemoteAddr()`.

The real client sits *third from the right*, but only because there
happen to be two trusted hops today. A hop count is a number that changes
silently when the platform changes, and this one has changed once already.

**`CF-Connecting-IP` being unforgeable is a platform assumption, not a
protocol guarantee.** It holds only while every request reaches this app
through Cloudflare — nothing in HTTP prevents a client from sending that
header, and Render gives this project no way to enforce that its instances
are unreachable except through the edge. It belongs in the same list as
the hop count: things that are true of the current platform and would
change without any code here changing. If a path that bypasses Cloudflare
ever exists, this resolver trusts whatever that path sends.

**`CF-Connecting-IP` is read instead, because Cloudflare *replaces* it
rather than appending to it.** A client-supplied value cannot survive the
edge, so there is nothing to strip, count hops through, or trust
conditionally. When the header is absent — local development, tests, any
path that never crossed the edge — the fallback is `getRemoteAddr()`,
which no client can set. That is a shared bucket: degraded, never
forgeable. **Every fallback in `CloudflareClientIpResolver` is to that same
address for that reason.**

The class sits behind the `ClientIpResolver` port and is named for the platform,
per `CLAUDE.md`'s rule that a third-party dependency lives behind an interface
this repository owns. Which header to trust is a fact about the deployment
rather than about this application — behind a different CDN, a different load
balancer, or nothing at all, the current one becomes forgeable — so moving is a
new class and a bean rather than an edit to logic other code depends on being
right. What the port adds beyond the usual seam is a contract that is a security
property: **the value an implementation returns must never be one the client
could have chosen.**

Two details keep that promise, and each closes half of the same hole — a
client's value surviving alongside the edge's:

- The header is read via `getHeaders` taking the **last** value, not
  `getHeader`, which returns only the first line of a repeated header.
- The value must parse as **one bare IP literal** or it is not used at
  all. RFC 9110 §5.3 makes two header lines and one comma-joined line
  interchangeable, and any recipient may fold one into the other, so the
  client's value can arrive *inside* the edge's line —
  `CF-Connecting-IP: 1.2.3.4, 208.54.226.138` would otherwise become the
  bucket key verbatim. The same guard rejects a port suffix, a scope id,
  an `unknown` token and any other junk, so every shape the resolver does
  not recognise becomes the shared bucket rather than a caller-controlled
  one. Parsing uses `InetAddress.ofLiteral`, which never performs a DNS
  lookup, and its canonical output means `[::1]` and `::1` cannot become
  two buckets.

This is also why `server.forward-headers-strategy` must stay `none`, and
the reason is not the obvious one: `ForwardedHeaderFilter` removes only
`Forwarded` and the `X-Forwarded-*` family, so `CF-Connecting-IP` would
still be readable under `framework`. What `framework` changes is
`getRemoteAddr()` itself — it rewrites it from the leftmost
`X-Forwarded-For` entry, which would make the *fallback* forgeable and
reinstate the original bypass through the safe path.

The alternative was `server.forward-headers-strategy: native` with
`server.tomcat.remoteip.internal-proxies` covering Tomcat's private-range
defaults plus Cloudflare's published ranges. `RemoteIpValve` walks right
to left and stops at the first entry that does not match, so it is
correct, and it never lands on a forgeable value either. It was not chosen
because it means tracking Cloudflare's published ranges as they change,
and a stale list fails quietly — the walk stops at an infrastructure
address and the shared bucket returns with nothing raised. A header
Cloudflare guarantees to overwrite has no list to go stale.

Two earlier revisions of this section, of `application.yml`, and of the
throttling code (then still inside `AuthController`, now in `ThrottleAspect`
and `CloudflareClientIpResolver`) got this wrong in two different ways: first
by claiming that transiting a proxy prevents forgery (it does not — only
replacement at the proxy would), then by assuming a single trusted hop and
keying on the rightmost entry, which is Render's shared load balancer.
[`CloudflareClientIpResolverTest`](../../src/test/java/com/zarlania/api/http/CloudflareClientIpResolverTest.java)
and
[`ClientIpThrottleEndToEndTest`](../../src/test/java/com/zarlania/api/auth/controllers/ClientIpThrottleEndToEndTest.java)
now build every case from the real three-entry header rather than a
two-entry approximation, which is what let both mistakes through: a test
that synthesizes `client, proxy` and then declares the last entry to be
the proxy has assumed its own conclusion.

## Outbound email has its own budget

Every limit above bounds requests, not mail — a caller staying inside the
`register` limit can still send more mail in a day than a free provider tier
allows. So outbound email carries a second cap, service-wide rather than
per-caller, spent through this same `RateLimiter` over a daily window instead of
the one-minute one. It appears in no table above, and its keys sit under
`zarlania.throttle` only because `ThrottleProperties` binds them. [Outbound
email](000003-outbound-email.md) owns the budget, its size, and the rest of the
sending path.
