import pytest

from docstooling.frontmatter import (
    FrontmatterError,
    render_frontmatter,
    split_frontmatter,
)


def test_split_returns_mapping_and_body():
    text = '---\nid: "000001"\ntitle: Hello\n---\n\n# Hello\n\nBody.\n'
    data, body = split_frontmatter(text)
    assert data == {"id": "000001", "title": "Hello"}
    assert body == "\n# Hello\n\nBody.\n"


def test_split_rejects_missing_opening_delimiter():
    with pytest.raises(FrontmatterError):
        split_frontmatter("# No frontmatter\n")


def test_split_rejects_unterminated_frontmatter():
    with pytest.raises(FrontmatterError):
        split_frontmatter("---\nid: x\n")


def test_split_rejects_non_mapping():
    with pytest.raises(FrontmatterError):
        split_frontmatter("---\n- just\n- a\n- list\n---\nbody\n")


def test_split_rejects_falsy_non_mapping():
    with pytest.raises(FrontmatterError):
        split_frontmatter("---\n[]\n---\nbody\n")


def test_split_rejects_invalid_yaml():
    with pytest.raises(FrontmatterError):
        split_frontmatter("---\nid: [unclosed\n---\nbody\n")


def test_split_allows_empty_frontmatter_block():
    data, body = split_frontmatter("---\n\n---\nbody\n")
    assert data == {}
    assert body == "body\n"


def test_render_round_trips_and_preserves_order():
    data = {"id": "000001", "title": "Hello", "tags": ["http"]}
    rendered = render_frontmatter(data)
    assert rendered.startswith("---\n")
    assert rendered.endswith("---\n")
    back, _ = split_frontmatter(rendered + "\nbody\n")
    assert back == data
    assert list(back.keys()) == ["id", "title", "tags"]


def test_split_rejects_duplicate_keys():
    with pytest.raises(FrontmatterError):
        split_frontmatter('---\nid: "1"\nid: "2"\n---\nbody\n')


def test_split_rejects_non_string_keys():
    with pytest.raises(FrontmatterError):
        split_frontmatter("---\n? [a, b]\n: v\n---\nbody\n")
