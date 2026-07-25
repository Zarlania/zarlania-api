"""CLI binding the shared doc tooling to the reference doc-type.

Usage (from docs/tooling): python references_cli.py <command> [...]
Commands: create, sync, validate, search, meta.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as _dt
import json
from pathlib import Path

from docstooling.config import REFERENCE, DocType
from docstooling.create import create
from docstooling.query import meta, search
from docstooling.sync import sync
from docstooling.validate import validate


def _split(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="references")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("sync")
    sub.add_parser("validate")
    meta_p = sub.add_parser("meta")
    meta_p.add_argument("--id", dest="only_id", default=None)
    search_p = sub.add_parser("search")
    search_p.add_argument("query")
    create_p = sub.add_parser("create")
    create_p.add_argument("--title", required=True)
    create_p.add_argument("--description", required=True)
    create_p.add_argument("--tags", default="")
    create_p.add_argument("--related", default="")
    return parser


def main(argv: list[str] | None = None, *, dt: DocType | None = None) -> int:
    dt = dt or REFERENCE
    args = _build_parser().parse_args(argv)

    if args.command == "sync":
        sync(dt)
        return 0
    if args.command == "validate":
        errors = validate(dt)
        for error in errors:
            print(error)
        return 1 if errors else 0
    if args.command == "meta":
        print(json.dumps(meta(dt, only_id=args.only_id), ensure_ascii=False, indent=2))
        return 0
    if args.command == "search":
        hits = [dataclasses.asdict(h) for h in search(dt, args.query)]
        print(json.dumps(hits, ensure_ascii=False, indent=2))
        return 0
    if args.command == "create":
        today = _dt.date.today().isoformat()
        path = create(
            dt,
            title=args.title,
            description=args.description,
            tags=_split(args.tags),
            related=_split(args.related),
            today=today,
        )
        try:
            display_path = path.relative_to(Path.cwd())
        except ValueError:
            display_path = path
        print(f"created {display_path}")
        return 0
    return 2  # unreachable: argparse enforces the subcommand


if __name__ == "__main__":
    raise SystemExit(main())
