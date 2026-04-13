"""Pydantic models — contracts shared with Java backend."""

from __future__ import annotations

from pydantic import BaseModel


class Position(BaseModel):
    x: int
    y: int


class AgentOut(BaseModel):
    externalId: str
    name: str
    role: str
    position: Position
    state: str = "idle"


class LayoutOut(BaseModel):
    width: int
    height: int
    backgroundPreset: str


class RoomOut(BaseModel):
    roomName: str
    theme: str
    layout: LayoutOut
    agents: list[AgentOut]


class GenerateRequest(BaseModel):
    prompt: str
    preset: str = "tech"


class AgentEvent(BaseModel):
    roomId: int
    agentExternalId: str
    eventType: str
    state: str
    x: int
    y: int
    message: str
    timestamp: str


class ChatMessage(BaseModel):
    id: int | None = None
    roomId: int
    agentExternalId: str | None = None
    senderType: str  # USER or AGENT
    content: str
    createdAt: str | None = None


class AgentResponse(BaseModel):
    """Response from the agent brain — text + optional action."""
    text: str
    action: str | None = None
    actionParams: dict | None = None


class SandboxFileEntry(BaseModel):
    path: str
    name: str
    size: int


class SandboxFileListResponse(BaseModel):
    roomId: int
    files: list[SandboxFileEntry]


class SandboxFileContentResponse(BaseModel):
    roomId: int
    path: str
    content: str
    size: int


class ContainerEvent(BaseModel):
    roomId: int
    status: str  # creating | running | stopped | error
    command: str = ""
    exitCode: int | None = None
    stdout: str = ""
    stderr: str = ""
    timedOut: bool = False
    timestamp: str = ""
