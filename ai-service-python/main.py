"""FastAPI entry point for Crab Office AI service."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException

from agent_brain import brain_loop
from agent_logic import move_agent
from architect import generate_room, get_llm_status
from models import AgentEvent, GenerateRequest, Position, RoomOut
from telegram_bot import run_telegram_bot

load_dotenv(Path(__file__).resolve().with_name(".env"))

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start the agent brain and Telegram bot on startup."""
    brain_task = asyncio.create_task(brain_loop())
    telegram_task = asyncio.create_task(run_telegram_bot())
    yield
    brain_task.cancel()
    telegram_task.cancel()
    for task in [brain_task, telegram_task]:
        try:
            await task
        except asyncio.CancelledError:
            pass


app = FastAPI(title="Crab Office AI Service", version="0.2.0", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", **get_llm_status()}


@app.post("/generate", response_model=RoomOut)
async def generate(req: GenerateRequest):
    """Generate an office room based on user prompt."""
    room = await generate_room(req.prompt, req.preset)
    return room


@app.post("/agents/event", response_model=AgentEvent)
async def agent_event(
    room_id: int,
    agent_external_id: str,
    current_state: str,
    target_state: str,
    x: int,
    y: int,
    message: str = "",
):
    """Trigger an agent state change and publish it via Redis."""
    result = await move_agent(
        room_id=room_id,
        agent_external_id=agent_external_id,
        current_state=current_state,
        target_state=target_state,
        new_position=Position(x=x, y=y),
        message=message,
    )
    if result is None:
        raise HTTPException(status_code=400, detail=f"Invalid transition {current_state} -> {target_state}")
    return result
