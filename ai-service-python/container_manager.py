"""Docker container manager for agent code execution.

Each room gets at most one container. Containers are created lazily
on the first ``run_code`` call and cleaned up after idle timeout.
All docker-py calls are wrapped in ``asyncio.to_thread`` so they
never block the FastAPI event loop.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

import docker
from docker.errors import NotFound

logger = logging.getLogger(__name__)

IMAGE_NAME = os.getenv("CRAB_RUNNER_IMAGE", "crab-agent-runner:latest")
NETWORK_NAME = os.getenv("CRAB_RUNNER_NETWORK", "crab-agent-net")
SANDBOX_ROOT = Path(__file__).resolve().parent.parent / "crab_sandbox"
MAX_CONTAINERS = int(os.getenv("CRAB_MAX_CONTAINERS", "5"))
EXEC_TIMEOUT = int(os.getenv("CRAB_EXEC_TIMEOUT", "30"))
IDLE_TIMEOUT = int(os.getenv("CRAB_IDLE_TIMEOUT", "600"))  # 10 min


@dataclass
class ExecResult:
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False


@dataclass
class _ContainerInfo:
    container_id: str
    room_id: int
    last_used: float = field(default_factory=time.time)


class ContainerManager:
    """Manages per-room Docker containers."""

    def __init__(self, client: docker.DockerClient | None = None):
        self._client = client or docker.from_env()
        self._containers: dict[int, _ContainerInfo] = {}

    # ── public async API ──────────────────────────────────────────

    async def get_or_create(self, room_id: int) -> str:
        return await asyncio.to_thread(self._get_or_create_sync, room_id)

    async def exec_command(
        self, room_id: int, command: str, timeout: int = EXEC_TIMEOUT
    ) -> ExecResult:
        return await asyncio.to_thread(
            self._exec_sync, room_id, command, timeout
        )

    async def stop_container(self, room_id: int) -> None:
        await asyncio.to_thread(self._stop_sync, room_id)

    async def cleanup_idle(self, max_idle_seconds: int = IDLE_TIMEOUT) -> int:
        return await asyncio.to_thread(self._cleanup_sync, max_idle_seconds)

    # ── sync internals (run inside thread) ────────────────────────

    def _get_or_create_sync(self, room_id: int) -> str:
        info = self._containers.get(room_id)
        if info:
            try:
                ct = self._client.containers.get(info.container_id)
                if ct.status == "running":
                    info.last_used = time.time()
                    return info.container_id
                # Stopped — restart
                ct.start()
                info.last_used = time.time()
                return info.container_id
            except NotFound:
                del self._containers[room_id]

        # Enforce container limit
        if len(self._containers) >= MAX_CONTAINERS:
            self._evict_oldest()

        sandbox_dir = SANDBOX_ROOT / f"room_{room_id}"
        sandbox_dir.mkdir(parents=True, exist_ok=True)

        container_name = f"crab-room-{room_id}"

        # Remove leftover container with same name
        try:
            old = self._client.containers.get(container_name)
            old.remove(force=True)
        except NotFound:
            pass

        ct = self._client.containers.run(
            image=IMAGE_NAME,
            name=container_name,
            network=NETWORK_NAME,
            volumes={
                str(sandbox_dir.resolve()): {
                    "bind": "/app/workspace",
                    "mode": "rw",
                }
            },
            mem_limit="512m",
            cpu_quota=50000,
            pids_limit=50,
            detach=True,
            tty=True,
            stdin_open=True,
        )

        self._containers[room_id] = _ContainerInfo(
            container_id=ct.id, room_id=room_id
        )
        logger.info(
            "Created container %s for room %d", container_name, room_id
        )
        return ct.id

    def _exec_sync(
        self, room_id: int, command: str, timeout: int = EXEC_TIMEOUT
    ) -> ExecResult:
        container_id = self._get_or_create_sync(room_id)
        ct = self._client.containers.get(container_id)

        info = self._containers.get(room_id)
        if info:
            info.last_used = time.time()

        exec_handle = self._client.api.exec_create(
            ct.id,
            ["sh", "-c", command],
            workdir="/app/workspace",
            stdout=True,
            stderr=True,
        )

        output = self._client.api.exec_start(exec_handle["Id"])
        # docker-py returns output as bytes
        stdout_text = output.decode("utf-8", errors="replace") if output else ""

        inspect = self._client.api.exec_inspect(exec_handle["Id"])
        exit_code = inspect.get("ExitCode", -1)

        # Truncate long output
        max_output = 4000
        if len(stdout_text) > max_output:
            stdout_text = stdout_text[:max_output] + "\n... (output truncated)"

        return ExecResult(
            exit_code=exit_code,
            stdout=stdout_text,
            stderr="",
            timed_out=False,
        )

    def _stop_sync(self, room_id: int) -> None:
        info = self._containers.pop(room_id, None)
        if not info:
            return
        try:
            ct = self._client.containers.get(info.container_id)
            ct.stop(timeout=5)
            ct.remove(force=True)
            logger.info("Stopped container for room %d", room_id)
        except NotFound:
            pass

    def _cleanup_sync(self, max_idle_seconds: int = IDLE_TIMEOUT) -> int:
        now = time.time()
        to_remove = [
            room_id
            for room_id, info in self._containers.items()
            if (now - info.last_used) > max_idle_seconds
        ]
        for room_id in to_remove:
            self._stop_sync(room_id)
        return len(to_remove)

    def _evict_oldest(self) -> None:
        if not self._containers:
            return
        oldest_room = min(
            self._containers, key=lambda rid: self._containers[rid].last_used
        )
        logger.warning("Evicting container for room %d (limit reached)", oldest_room)
        self._stop_sync(oldest_room)


# Module-level singleton
_manager: ContainerManager | None = None


def get_container_manager() -> ContainerManager:
    global _manager
    if _manager is None:
        _manager = ContainerManager()
    return _manager
