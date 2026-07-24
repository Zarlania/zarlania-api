from docstooling.create import create, slugify
from docstooling.validate import validate


def test_slugify():
    assert slugify("Hello, World! 2") == "hello-world-2"


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
