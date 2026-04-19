ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS location_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS sprite_key VARCHAR(100),
    ADD COLUMN IF NOT EXISTS status_text VARCHAR(500),
    ADD COLUMN IF NOT EXISTS current_task_id BIGINT,
    ADD COLUMN IF NOT EXISTS target_agent_external_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS locations (
    id                  BIGSERIAL PRIMARY KEY,
    room_id             BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    location_key        VARCHAR(100) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    kind                VARCHAR(100) NOT NULL,
    width               INT NOT NULL,
    height              INT NOT NULL,
    background_preset   VARCHAR(255) NOT NULL,
    spawn_points_json   TEXT NOT NULL,
    interaction_points_json TEXT NOT NULL,
    sort_order          INT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_locations_room_key UNIQUE (room_id, location_key)
);

CREATE TABLE IF NOT EXISTS simulation_events (
    id                  BIGSERIAL PRIMARY KEY,
    room_id             BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    location_id         VARCHAR(100),
    agent_external_id   VARCHAR(255),
    event_type          VARCHAR(100) NOT NULL,
    state               VARCHAR(100),
    payload_json        TEXT NOT NULL,
    correlation_id      VARCHAR(255),
    run_id              VARCHAR(255),
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_locations_room_id ON locations(room_id);
CREATE INDEX IF NOT EXISTS idx_simulation_events_room_id ON simulation_events(room_id);
CREATE INDEX IF NOT EXISTS idx_simulation_events_room_created_at ON simulation_events(room_id, created_at DESC);
