# 🦀 Crab Office

Платформа для управления ИИ-агентами в виде виртуального офиса.

## Техстек

- **AI Logic:** Python (FastAPI + OpenRouter)
- **Backend:** Java 21 (Spring Boot)
- **Frontend:** Next.js (Tailwind + Framer Motion)
- **Infrastructure:** Local PC (GTX 1070) + Cloudflare Tunnel

## Структура репозитория

- `ai-service-python/` — сервис ИИ-логики и интеграций.
- `backend-java/` — основной backend на Spring Boot.
- `frontend-nextjs/` — клиентское приложение на Next.js.

## Общее направление

В этом репозитории собирается единая платформа, где ИИ-агенты работают как сотрудники виртуального офиса: взаимодействуют через интерфейс, обмениваются задачами и используют backend и AI-сервисы как общую основу для бизнес-логики.

## Быстрый запуск

### 1. Инфраструктура (PostgreSQL + Redis)

```bash
docker compose up -d
```

### 2. AI-service (Python)

```bash
cd ai-service-python
cp .env.example .env          # key goes into ai-service-python/.env
python -m venv .venv
.venv\Scripts\activate         # Windows
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

In ai-service-python/.env set:

```env
OPENROUTER_API_KEY=your-openrouter-key-here
OPENROUTER_MODEL=deepseek/deepseek-v3.2
```

If OPENROUTER_API_KEY is empty, the AI service stays in mock mode.

### 3. Backend (Java)

```bash
cd backend-java
.\mvnw spring-boot:run
```

> Требуется Java 21. Postgres и Redis должны быть запущены.

### 4. Frontend (Next.js)

```bash
cd frontend-nextjs
npm install
npm run dev
```

Затем откройте http://localhost:3000.

### Порты

| Сервис       | Порт |
|-------------|------|
| Frontend    | 3000 |
| Backend     | 8080 |
| AI Service  | 8000 |
| PostgreSQL  | 5432 |
| Redis       | 6379 |

## План реализации

- Подробный реализационный план: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)

## Текущая стадия

Сейчас репозиторий находится на этапе первого сквозного прототипа:

- генерация офисной комнаты и агентов через OpenRouter/DeepSeek;
- backend сохраняет комнаты, агентов, сообщения и задачи, а также рассылает события через WebSocket и Redis;
- frontend показывает офис, чат и базовую task-панель;
- AI service запущен не только как генератор комнаты, но и как agent brain c Telegram bridge.

Это уже рабочая интеграционная вертикаль, но еще не production-ready версия: впереди авторизация, нормализация Telegram UX, расширение автономности агентов и операционная обвязка.