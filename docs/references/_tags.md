# Reference tags

Every tag used by a reference doc must appear in the first column of the table
below, and tags are kept in alphabetical order. `references_cli.py validate`
fails on any tag that is not registered here. Reuse an existing tag where it
fits; only add a new row when none of these cover the change.

| Tag | Description |
| --- | ----------- |
| architecture | Cross-cutting structure: modules, boundaries, how pieces fit. |
| build | Build, packaging, and the Maven setup. |
| ci | Continuous integration and quality gates. |
| configuration | Application configuration and environment overrides. |
| controllers | Spring MVC controllers and request mapping. |
| http | HTTP request/response handling and endpoints. |
