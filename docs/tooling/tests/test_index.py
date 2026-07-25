from docstooling.document import load_all
from docstooling.index import render_index
from tests.conftest import write_doc


def test_render_index_one_row_per_doc_sorted(reference_root):
    write_doc(reference_root, "000002", "beta", title="Beta", tags=["http"], related=[])
    write_doc(reference_root, "000001", "alpha", title="Alpha", tags=[], related=[])
    out = render_index(load_all(reference_root))
    lines = out.splitlines()
    assert lines[0] == "| ID | Title | Description | Tags |"
    assert lines[2].startswith("| [000001](000001-alpha.md) | Alpha |")
    assert lines[3].startswith("| [000002](000002-beta.md) | Beta |")
    assert "| http |" in lines[3]


def test_render_index_escapes_pipe_in_title(reference_root):
    write_doc(reference_root, "000001", "hello", title="a | b", tags=[], related=[])
    out = render_index(load_all(reference_root))
    assert "a \\| b" in out
