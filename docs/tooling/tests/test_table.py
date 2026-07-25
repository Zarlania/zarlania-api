from docstooling.document import load_all
from docstooling.table import render_table
from tests.conftest import write_doc


def test_render_table_includes_all_fields_and_related_link(reference_root):
    write_doc(reference_root, "000002", "target", title="Target", tags=[], related=[])
    write_doc(
        reference_root,
        "000001",
        "hello",
        title="Hello",
        tags=["http", "controllers"],
        related=["000002"],
    )
    docs = load_all(reference_root)
    by_id = {d.id: d for d in docs}
    table = render_table(by_id["000001"], by_id)
    assert "| ID | 000001 |" in table
    assert "| Title | Hello |" in table
    assert "| Tags | http, controllers |" in table
    assert "| Related | [000002](000002-target.md) |" in table


def test_render_table_uses_dash_for_empty_lists(reference_root):
    write_doc(reference_root, "000001", "hello", title="Hello", tags=[], related=[])
    docs = load_all(reference_root)
    table = render_table(docs[0], {d.id: d for d in docs})
    assert "| Tags | — |" in table
    assert "| Related | — |" in table


def test_render_table_escapes_pipe_in_title(reference_root):
    write_doc(reference_root, "000001", "hello", title="a | b", tags=[], related=[])
    docs = load_all(reference_root)
    table = render_table(docs[0], {d.id: d for d in docs})
    assert "| Title | a \\| b |" in table
