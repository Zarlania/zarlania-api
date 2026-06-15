import lib


def test_slugify_basic():
    assert lib.slugify("Use Postgres for Persistence!") == "use-postgres-for-persistence"


def test_slugify_collapses_separators():
    assert lib.slugify("  A / B  --  C ") == "a-b-c"


def test_field_labels_cover_schema():
    assert lib.FIELD_LABELS["id"] == "ID"
    assert "tags" in lib.FIELD_LABELS
    assert len(lib.FIELD_LABELS) == 11
