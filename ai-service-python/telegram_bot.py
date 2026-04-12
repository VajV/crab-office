"""Telegram bot — bridges Telegram <-> Crab Office chat via Redis."""

from __future__ import annotations

import asyncio
import json
import logging
import os

import redis.asyncio as aioredis
from aiogram import Bot, Dispatcher, types
from aiogram.filters import CommandStart

logger = logging.getLogger(__name__)

REDIS_INBOUND = "crab:chat-inbound"
REDIS_OUTBOUND = "crab:chat-outbound"

# Map telegram chat_id -> room_id
_chat_room_map: dict[int, int] = {}


def _get_redis() -> aioredis.Redis:
    return aioredis.from_url(os.getenv("REDIS_URL", "redis://localhost:6379"))


def _get_default_room_id() -> int:
    return int(os.getenv("TELEGRAM_DEFAULT_ROOM_ID", "1"))


bot: Bot | None = None
dp = Dispatcher()


@dp.message(CommandStart())
async def cmd_start(message: types.Message) -> None:
    room_id = _get_default_room_id()
    _chat_room_map[message.chat.id] = room_id
    await message.answer(
        f"\U0001f980 Crab Office подключён!\n"
        f"Вы в комнате #{room_id}. Пишите сообщения — агенты ответят."
    )


@dp.message()
async def handle_message(message: types.Message) -> None:
    if not message.text:
        return

    chat_id = message.chat.id
    room_id = _chat_room_map.get(chat_id, _get_default_room_id())

    outbound = {
        "roomId": room_id,
        "senderType": "USER",
        "content": message.text,
        "createdAt": message.date.isoformat() if message.date else None,
    }

    r = _get_redis()
    await r.publish(REDIS_INBOUND, json.dumps(outbound))
    await r.aclose()


async def _relay_agent_responses(bot_instance: Bot) -> None:
    """Subscribe to crab:chat-outbound and relay agent responses to Telegram."""
    r = _get_redis()
    pubsub = r.pubsub()
    await pubsub.subscribe(REDIS_OUTBOUND)

    try:
        async for raw in pubsub.listen():
            if raw["type"] != "message":
                continue
            data = raw["data"]
            if isinstance(data, bytes):
                data = data.decode("utf-8")
            try:
                msg = json.loads(data)
                room_id = msg.get("roomId")
                agent_name = msg.get("agentExternalId", "Agent")
                content = msg.get("content", "")

                for chat_id, rid in _chat_room_map.items():
                    if rid == room_id:
                        await bot_instance.send_message(
                            chat_id,
                            f"\U0001f916 *{agent_name}*:\n{content}",
                            parse_mode="Markdown",
                        )
            except Exception:
                logger.exception("Failed to relay agent response to Telegram")
    except asyncio.CancelledError:
        pass
    finally:
        await pubsub.unsubscribe(REDIS_OUTBOUND)
        await r.aclose()


async def run_telegram_bot() -> None:
    """Start the Telegram bot with polling."""
    token = os.getenv("TELEGRAM_BOT_TOKEN", "")
    if not token:
        logger.warning("TELEGRAM_BOT_TOKEN not set — Telegram bot disabled")
        return

    global bot
    bot = Bot(token=token)
    relay_task = asyncio.create_task(_relay_agent_responses(bot))

    try:
        logger.info("Telegram bot starting (polling mode)")
        await dp.start_polling(bot)
    finally:
        relay_task.cancel()
        try:
            await relay_task
        except asyncio.CancelledError:
            pass
        await bot.session.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    from pathlib import Path
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().with_name(".env"))
    asyncio.run(run_telegram_bot())
