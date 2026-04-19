from pathlib import Path
import sys

sys.path.append(str(Path(__file__).resolve().parents[1]))

from runtime_command_parser import parse_runtime_command


def test_parse_russian_create_agent_command():
    parsed = parse_runtime_command("создай SEO")
    assert parsed is not None
    assert parsed["commandType"] == "spawn_agent"
    assert parsed["payload"]["role"] == "seo"


def test_parse_english_create_agent_command():
    parsed = parse_runtime_command("create developer agent")
    assert parsed is not None
    assert parsed["commandType"] == "spawn_agent"
    assert parsed["payload"]["role"] == "developer"


def test_ignore_regular_message():
    assert parse_runtime_command("как дела у команды?") is None


def test_parse_assign_task_command():
    parsed = parse_runtime_command("назначь seo задачу подготовить seo brief")
    assert parsed is not None
    assert parsed["commandType"] == "assign_task"
    assert parsed["payload"]["role"] == "seo"


def test_parse_start_interaction_command():
    parsed = parse_runtime_command("пусть seo поговорит с копирайтер")
    assert parsed is not None
    assert parsed["commandType"] == "start_interaction"
    assert parsed["payload"]["initiatorRole"] == "seo"
    assert parsed["payload"]["targetRole"] == "copywriter"
