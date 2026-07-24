from docstooling.tags import load_tags


def test_load_tags_reads_first_column(reference_root):
    tags = load_tags(reference_root / "_tags.md")
    assert tags == {"http", "controllers"}
