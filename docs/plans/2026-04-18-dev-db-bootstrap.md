# Dev DB Bootstrap

## Problem

Local developer databases may predate Flyway and contain only the legacy prototype tables:

- `rooms`
- `agents`
- `messages`
- `tasks`
- `container_logs`

This causes modern backend startup to fail because current JPA entities expect additional tables and columns such as:

- `agent_actions`
- `locations`
- `simulation_events`
- richer `agents` columns

## Expected dev startup

1. Start Docker infra:

```bash
docker compose up -d postgres redis openclaw-gateway
```

2. Backend should connect to:

- host: `localhost`
- port: `5433`
- db: `craboffice`
- user: `crab`
- password: `crab_secret`

3. Backend should start with:

```bash
cd backend-java
.\mvnw spring-boot:run
```

## If local DB was created before Flyway

Use one of two safe dev paths.

### Path A: recreate dev database

If you do not need existing local test data:

```bash
docker compose down -v
docker compose up -d postgres redis openclaw-gateway
```

Then start backend again.

### Path B: patch legacy dev schema forward

If you want to preserve local dev data, apply the current schema additions before starting backend.

Required additions:

- `agents.location_id`
- `agents.sprite_key`
- `agents.status_text`
- `agents.current_task_id`
- `agents.target_agent_external_id`
- `agent_actions`
- `locations`
- `simulation_events`

This repo currently contains Flyway migrations for these additions:

- `V2__visual_office_world.sql`
- `V3__agent_actions_table.sql`

If the database was created outside Flyway and has no `flyway_schema_history`, recreate the dev DB unless preserving local test data is mandatory.

## Recommendation

For consistent local startup, prefer `Path A` during active feature development. It is the least ambiguous route and avoids hidden schema drift from earlier prototypes.
