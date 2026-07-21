<!--
  Title format: #<issue-number> <type>: <description>
  Example:      #42 feat: add hello endpoint
  Types:        feat, fix, chore, docs, refactor, perf, test, build, ci, style, revert

  Your branch must be named <issue-number>-<slug>, e.g. 42-add-hello-endpoint.
  The PR Lint workflow enforces all of this.
-->

Closes #

## What changed

<!-- A short description of the change and why it is needed. -->

## How to verify

<!-- Steps a reviewer can follow to confirm this works. -->

1.

## Checklist

- [ ] Branch is named `<issue-number>-<slug>` and the title starts with `#<issue-number>`
- [ ] `Closes #<issue-number>` above references the tracking issue
- [ ] A `major`, `minor` or `patch` label is applied (this sets the released version)
- [ ] `./mvnw verify` passes locally
- [ ] Tests cover the change
- [ ] Documentation is updated if behaviour changed

## Notes for reviewers

<!-- Trade-offs, follow-up work, or anything deliberately left out. Delete if unused. -->
