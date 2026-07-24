import pytest

from docstooling.markers import MarkerError, extract_region, replace_region

DOC = "before\n<!-- t:start -->\nOLD\n<!-- t:end -->\nafter\n"


def test_replace_region_swaps_inner_content():
    out = replace_region(DOC, "t", "NEW")
    assert "<!-- t:start -->\nNEW\n<!-- t:end -->" in out
    assert "OLD" not in out
    assert out.startswith("before\n")
    assert out.endswith("after\n")


def test_replace_region_is_idempotent():
    once = replace_region(DOC, "t", "NEW")
    assert replace_region(once, "t", "NEW") == once


def test_extract_region_returns_inner():
    assert extract_region(DOC, "t") == "OLD"


def test_missing_markers_raise():
    with pytest.raises(MarkerError):
        replace_region("no markers", "t", "x")
    with pytest.raises(MarkerError):
        extract_region("no markers", "t")
