"""Async HTTP client for the Java Task REST API."""

from __future__ import annotations

import logging
import os

import httpx

logger = logging.getLogger(__name__)

BACKEND_URL = os.getenv("JAVA_BACKEND_URL", "http://localhost:8080")


async def create_task(
    room_id: int, title: str, description: str, agent_external_id: str
) -> dict | None:
    """POST /api/rooms/{roomId}/tasks — returns the created TaskDto."""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks"
    payload = {
        "title": title,
        "description": description,
        "assignedAgentExternalId": agent_external_id,
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to create task via Java API")
        return None


async def update_task_status(task_id: int, room_id: int, status: str) -> dict | None:
    """PATCH /api/rooms/{roomId}/tasks/{taskId}/status"""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks/{task_id}/status"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.patch(url, json={"status": status})
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to update task status via Java API")
        return None


async def update_task_result(task_id: int, room_id: int, result: str) -> dict | None:
    """PATCH /api/rooms/{roomId}/tasks/{taskId}/result"""
    url = f"{BACKEND_URL}/api/rooms/{room_id}/tasks/{task_id}/result"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.patch(url, json={"result": result})
            resp.raise_for_status()
            return resp.json()
    except Exception:
        logger.exception("Failed to update task result via Java API")
        return None
