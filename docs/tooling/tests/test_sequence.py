from dataclasses import replace
from pathlib import Path

from docstooling.document import Document
from docstooling.sequence import next_id, validate_sequence


def _doc(doc_id: str) -> Document:
    return Document(
        id=doc_id,
        title="t",
        description="d",
        tags=[],
        created="c",
        updated="u",
        related=[],
        body="",
        path=Path(f"{doc_id}-x.md"),
    )


def test_next_id_starts_at_one_when_empty():
    assert next_id([], 6) == "000001"


def test_next_id_increments_max():
    assert next_id([_doc("000001"), _doc("000004")], 6) == "000005"


def test_validate_sequence_accepts_contiguous():
    assert validate_sequence([_doc("000001"), _doc("000002")], 6) == []


def test_validate_sequence_flags_gap():
    errors = validate_sequence([_doc("000001"), _doc("000003")], 6)
    assert any("gap" in e for e in errors)


def test_validate_sequence_flags_duplicate():
    errors = validate_sequence([_doc("000001"), _doc("000001")], 6)
    assert any("duplicate" in e for e in errors)


def test_validate_sequence_flags_bad_width():
    errors = validate_sequence([replace(_doc("1"), id="1")], 6)
    assert any("6 digits" in e for e in errors)


def test_validate_sequence_rejects_unicode_digit_lookalikes_without_crashing():
    # '²' is str.isdigit()==True but str.isdecimal()==False and int() can't parse it.
    errors = validate_sequence([_doc("²²²²²²")], 6)
    assert any("6 digits" in e for e in errors)  # returns an error, does not raise


def test_validate_sequence_rejects_same_width_non_digit():
    errors = validate_sequence([_doc("abcdef")], 6)
    assert any("6 digits" in e for e in errors)
