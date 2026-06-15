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
        print(
            f"error: unknown tag(s) {unknown}; add them first with `./scripts/adr add-tag`",
            file=sys.stderr,
        )
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

    n = sub.add_parser("new")
    n.add_argument("--name", required=True)
    n.add_argument("--tags", required=True, help="comma-separated")
    n.add_argument("--author", default="stimothy")
    n.set_defaults(fn=_cmd_new)

    li = sub.add_parser("list")
    li.add_argument("--status")
    li.add_argument("--tag")
    li.set_defaults(fn=_cmd_list)

    f = sub.add_parser("find")
    f.add_argument("query")
    f.set_defaults(fn=_cmd_find)

    s = sub.add_parser("show")
    s.add_argument("id")
    s.set_defaults(fn=_cmd_show)

    sub.add_parser("tags").set_defaults(fn=_cmd_tags)

    at = sub.add_parser("add-tag")
    at.add_argument("tag")
    at.add_argument("--description", required=True)
    at.set_defaults(fn=_cmd_add_tag)

    sub.add_parser("tag-usage").set_defaults(fn=_cmd_tag_usage)

    bt = sub.add_parser("by-tag")
    bt.add_argument("tag")
    bt.set_defaults(fn=_cmd_by_tag)

    ac = sub.add_parser("accept")
    ac.add_argument("id")
    ac.set_defaults(fn=_cmd_accept)

    sub.add_parser("index").set_defaults(fn=_cmd_index)
    sub.add_parser("check").set_defaults(fn=_cmd_check)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.fn(args, Path(args.adr_dir))


if __name__ == "__main__":
    raise SystemExit(main())
