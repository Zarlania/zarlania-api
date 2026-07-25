import pytest

from docstooling.create import create, slugify
from docstooling.document import load_document
from docstooling.validate import validate


def test_slugify():
    assert slugify("Hello, World! 2") == "hello-world-2"


def test_slugify_fallback_for_punctuation_only_title():
    assert slugify("!!!") == "untitled"


def test_create_allocates_id_and_validates(reference_dt):
    path = create(
        reference_dt,
        title="Hello World",
        description="A greeting.",
        tags=["http"],
        related=[],
        today="2026-07-23",
    )
    assert path.name == "000001-hello-world.md"
    assert validate(reference_dt) == []


def test_create_increments_ids(reference_dt):
    create(reference_dt, title="First", description="d", tags=[], related=[], today="2026-07-23")
    second = create(
        reference_dt,
        title="Second",
        description="d",
        tags=[],
        related=["000001"],
        today="2026-07-23",
    )
    assert second.name.startswith("000002-")
    assert validate(reference_dt) == []


def test_create_sorts_tags_alphabetically(reference_dt):
    path = create(
        reference_dt,
        title="Sorted",
        description="d",
        tags=["http", "controllers"],
        related=[],
        today="2026-07-24",
    )
    assert load_document(path).tags == ["controllers", "http"]


def test_create_rejects_unknown_tag(reference_dt):
    with pytest.raises(ValueError, match="unknown tags"):
        create(
            reference_dt,
            title="Nope",
            description="d",
            tags=["nonexistent"],
            related=[],
            today="2026-07-24",
        )


def test_create_rejects_unknown_related(reference_dt):
    with pytest.raises(ValueError, match="unknown related"):
        create(
            reference_dt,
            title="Nope",
            description="d",
            tags=[],
            related=["000999"],
            today="2026-07-24",
        )


def test_create_treats_title_as_literal_in_h1(reference_dt):
    # A backslash in the title must not be interpreted as a regex replacement.
    path = create(
        reference_dt,
        title=r"Back\slash",
        description="d",
        tags=[],
        related=[],
        today="2026-07-24",
    )
    assert r"# Back\slash" in path.read_text(encoding="utf-8")
