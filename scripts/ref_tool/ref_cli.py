"""Command-line interface for reference docs. Run via ./scripts/ref."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import ref


def _find_by_id(ref_dir: Path, doc_id: str) -> ref.core.Doc | None:
    for doc in ref.iter_docs(ref_dir):
        if str(doc.frontmatter.get("id")) == doc_id:
            return doc
    return None


def _cmd_new(args, ref_dir: Path) -> int:
    tags = [t.strip() for t in args.tags.split(",") if t.strip()]
    tags_path = ref_dir / "_tags.md"
    if not tags_path.exists():
        print(f"error: tag registry not found: {tags_path}", file=sys.stderr)
        return 1
    registry = ref.load_tags(tags_path)
    unknown = [t for t in tags if t not in registry]
    if unknown:
        print(
            f"error: unknown tag(s) {unknown}; add them first with `./scripts/ref add-tag`",
            file=sys.stderr,
        )
        return 1
    try:
        path = ref.new_doc(ref_dir, title=args.title, tags=tags)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    ref.write_index(ref_dir)
    print(f"created {path}")
    return 0


def _cmd_list(args, ref_dir: Path) -> int:
    for doc in ref.iter_docs(ref_dir):
        fm = doc.frontmatter
        if args.tag and args.tag not in (fm.get("tags") or []):
            continue
        print(f"{fm['id']}  {fm['title']}")
    return 0


def _cmd_find(args, ref_dir: Path) -> int:
    needle = args.query.lower()
    for doc in ref.iter_docs(ref_dir):
        if needle in doc.path.read_text(encoding="utf-8").lower():
            print(f"{doc.frontmatter['id']}  {doc.path.name}")
    return 0


def _cmd_show(args, ref_dir: Path) -> int:
    doc = _find_by_id(ref_dir, args.id)
    if not doc:
        print(f"error: no reference doc with id {args.id}", file=sys.stderr)
        return 1
    print(ref.dump_frontmatter(doc.frontmatter))
    return 0


def _cmd_tags(args, ref_dir: Path) -> int:
    tags_path = ref_dir / "_tags.md"
    if not tags_path.exists():
        print(f"error: tag registry not found: {tags_path}", file=sys.stderr)
        return 1
    for tag, desc in sorted(ref.load_tags(tags_path).items()):
        print(f"{tag:<16}  {desc}")
    return 0


def _cmd_add_tag(args, ref_dir: Path) -> int:
    tags_path = ref_dir / "_tags.md"
    if not tags_path.exists():
        print(f"error: tag registry not found: {tags_path}", file=sys.stderr)
        return 1
    if args.tag in ref.load_tags(tags_path):
        print(f"tag '{args.tag}' is already registered")
        return 0
    try:
        ref.add_tag(tags_path, args.tag, args.description)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"registered tag '{args.tag}'")
    return 0


def _cmd_tag_usage(args, ref_dir: Path) -> int:
    tags_path = ref_dir / "_tags.md"
    if not tags_path.exists():
        print(f"error: tag registry not found: {tags_path}", file=sys.stderr)
        return 1
    counts: dict[str, int] = {t: 0 for t in ref.load_tags(tags_path)}
    for doc in ref.iter_docs(ref_dir):
        for tag in doc.frontmatter.get("tags") or []:
            counts[tag] = counts.get(tag, 0) + 1
    for tag, n in sorted(counts.items()):
        print(f"{tag:<16}  {n}")
    return 0


def _cmd_by_tag(args, ref_dir: Path) -> int:
    for doc in ref.iter_docs(ref_dir):
        if args.tag in (doc.frontmatter.get("tags") or []):
            print(f"{doc.frontmatter['id']}  {doc.frontmatter['title']}")
    return 0


def _cmd_index(args, ref_dir: Path) -> int:
    ref.write_index(ref_dir)
    print("index regenerated")
    return 0


def _cmd_check(args, ref_dir: Path) -> int:
    errors = ref.validate_docs(ref_dir)
    if errors:
        for e in errors:
            print(f"REF CHECK: {e}", file=sys.stderr)
        return 1
    print("reference check passed")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="ref", description="Manage reference docs.")
    p.add_argument("--ref-dir", default="docs/reference", help="reference docs directory")
    sub = p.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("new")
    n.add_argument("--title", required=True)
    n.add_argument("--tags", required=True, help="comma-separated")
    n.set_defaults(fn=_cmd_new)

    li = sub.add_parser("list")
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

    sub.add_parser("index").set_defaults(fn=_cmd_index)
    sub.add_parser("check").set_defaults(fn=_cmd_check)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.fn(args, Path(args.ref_dir))


if __name__ == "__main__":
    raise SystemExit(main())
