# Security Policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Report privately using GitHub's
[private vulnerability reporting](https://github.com/Zarlania/zarlania-api/security/advisories/new).
This creates a confidential advisory visible only to maintainers.

Please include as much of the following as you can:

- The type of issue (for example: injection, authentication bypass, secret exposure)
- The affected version, release tag or commit SHA
- Step-by-step instructions to reproduce it
- Proof-of-concept code, if you have it
- The impact — what an attacker could achieve

## What to expect

| Stage                | Target                                                    |
| -------------------- | --------------------------------------------------------- |
| Acknowledgement      | Within 5 business days                                     |
| Initial assessment   | Within 10 business days                                    |
| Fix or mitigation    | Depends on severity and complexity; you will be kept informed |

We will let you know when the issue is resolved and, unless you prefer otherwise,
credit you in the advisory.

Please give us a reasonable opportunity to release a fix before public disclosure.

## Supported versions

> **Placeholder.** This project has not reached a stable release. Until then, only
> the latest release on `master` receives security fixes. A formal support matrix
> will be published at 1.0.

| Version        | Supported |
| -------------- | --------- |
| Latest release | Yes       |
| Anything older | No        |

## Scope

In scope: the source code in this repository and its deployment configuration.

Out of scope: vulnerabilities in third-party dependencies (report those upstream,
though we do want to hear if we are pinned to a vulnerable version), and findings
that require physical access or a compromised developer machine.

## Our practices

- [Gitleaks](https://github.com/gitleaks/gitleaks) scans every push and pull
  request, plus a weekly full-history sweep.
- [CodeQL](https://codeql.github.com/) static analysis runs on every push, pull
  request, and weekly.
- [Dependabot](https://docs.github.com/code-security/dependabot) opens weekly
  dependency updates.

If you find a secret committed to this repository, treat it as a vulnerability and
report it privately so it can be rotated.
