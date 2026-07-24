from docstooling.cells import escape_cell


def test_escape_cell_escapes_pipe():
    assert escape_cell("a | b") == "a \\| b"


def test_escape_cell_collapses_line_breaks():
    assert escape_cell("a\r\nb\nc\rd") == "a b c d"


def test_escape_cell_leaves_plain_text_unchanged():
    assert escape_cell("hello world") == "hello world"
