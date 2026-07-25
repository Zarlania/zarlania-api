from docstooling.query import meta, search
from tests.conftest import write_doc


def _seed(root):
    write_doc(root, "000001", "hello", title="Hello", tags=["http"], related=["000002"])
    write_doc(root, "000002", "world", title="World", tags=["controllers"], related=[])


def test_meta_returns_fields_without_body(reference_dt):
    _seed(reference_dt.root)
    rows = meta(reference_dt)
    assert [r["id"] for r in rows] == ["000001", "000002"]
    assert rows[0]["tags"] == ["http"]
    assert "body" not in rows[0]


def test_meta_filters_by_id(reference_dt):
    _seed(reference_dt.root)
    rows = meta(reference_dt, only_id="000002")
    assert [r["id"] for r in rows] == ["000002"]


def test_search_matches_title(reference_dt):
    _seed(reference_dt.root)
    hits = search(reference_dt, "world")
    assert [h.id for h in hits] == ["000002"]


def test_search_matches_body_with_snippet(reference_dt):
    _seed(reference_dt.root)
    hits = search(reference_dt, "Body of Hello")
    assert hits[0].id == "000001"
    assert "Body of Hello" in (hits[0].snippet or "")
