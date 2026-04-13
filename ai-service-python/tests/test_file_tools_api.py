from pathlib import Path

from file_tools import list_files_structured, read_file_payload


def test_list_files_structured_returns_relative_paths(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_4"
    room_dir.mkdir(parents=True)
    (room_dir / "src").mkdir(parents=True)
    (room_dir / "src" / "App.java").write_text("class App {}", encoding="utf-8")

    payload = list_files_structured(4)

    assert payload[0]["path"] == "src/App.java"
    assert payload[0]["size"] > 0


def test_read_file_payload_returns_file_content(tmp_path, monkeypatch):
    monkeypatch.setattr("file_tools.SANDBOX_ROOT", tmp_path)
    room_dir = tmp_path / "room_4"
    room_dir.mkdir(parents=True)
    (room_dir / "README.md").write_text("hello", encoding="utf-8")

    payload = read_file_payload(4, "README.md")

    assert payload["path"] == "README.md"
    assert payload["content"] == "hello"
