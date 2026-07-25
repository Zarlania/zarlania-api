import json

from references_cli import main
from tests.conftest import write_doc


def test_validate_ok_returns_zero(reference_dt, capsys):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    from docstooling.sync import sync

    sync(reference_dt)
    assert main(["validate"], dt=reference_dt) == 0


def test_validate_bad_returns_one(reference_dt, capsys):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["nope"], related=[])
    from docstooling.sync import sync

    sync(reference_dt)
    assert main(["validate"], dt=reference_dt) == 1
    assert "unknown tag" in capsys.readouterr().out


def test_create_then_meta_json(reference_dt, capsys):
    assert (
        main(
            ["create", "--title", "Hello", "--description", "d", "--tags", "http"],
            dt=reference_dt,
        )
        == 0
    )
    capsys.readouterr()
    assert main(["meta"], dt=reference_dt) == 0
    rows = json.loads(capsys.readouterr().out)
    assert rows[0]["title"] == "Hello"


def test_search_json(reference_dt, capsys):
    main(["create", "--title", "Hello", "--description", "d"], dt=reference_dt)
    capsys.readouterr()
    assert main(["search", "hello"], dt=reference_dt) == 0
    hits = json.loads(capsys.readouterr().out)
    assert hits[0]["id"] == "000001"


def test_sync_cli_fills_index(reference_dt):
    write_doc(reference_dt.root, "000001", "hello", title="Hello", tags=["http"], related=[])
    assert main(["sync"], dt=reference_dt) == 0
    from docstooling.markers import extract_region

    readme = reference_dt.readme_path.read_text()
    assert "[000001](000001-hello.md)" in extract_region(readme, "reference-index")
