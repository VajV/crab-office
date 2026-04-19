-- V1: Baseline schema matching existing JPA entities

CREATE TABLE IF NOT EXISTS rooms (
    id                  BIGSERIAL PRIMARY KEY,
    room_name           VARCHAR(255),
    theme               VARCHAR(255),
    layout_width        INT NOT NULL DEFAULT 0,
    layout_height       INT NOT NULL DEFAULT 0,
    background_preset   VARCHAR(255),
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agents (
    id          BIGSERIAL PRIMARY KEY,
    external_id VARCHAR(255),
    name        VARCHAR(255),
    role        VARCHAR(255),
    pos_x       INT NOT NULL DEFAULT 0,
    pos_y       INT NOT NULL DEFAULT 0,
    state       VARCHAR(255),
    room_id     BIGINT REFERENCES rooms(id)
);

CREATE TABLE IF NOT EXISTS messages (
    id                  BIGSERIAL PRIMARY KEY,
    room_id             BIGINT NOT NULL,
    agent_external_id   VARCHAR(255),
    sender_type         VARCHAR(50) NOT NULL,
    content             TEXT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_actions (
    id                  BIGSERIAL PRIMARY KEY,
    room_id             BIGINT NOT NULL,
    agent_external_id   VARCHAR(255),
    action_type         VARCHAR(100) NOT NULL,
    tool_name           VARCHAR(255),
    status              VARCHAR(50) NOT NULL,
    params              TEXT,
    result              TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tasks (
    id                          BIGSERIAL PRIMARY KEY,
    room_id                     BIGINT NOT NULL,
    assigned_agent_external_id  VARCHAR(255),
    title                       VARCHAR(500) NOT NULL,
    description                 TEXT,
    result                      TEXT,
    status                      VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at                  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS container_logs (
    id          BIGSERIAL PRIMARY KEY,
    room_id     BIGINT NOT NULL,
    status      VARCHAR(50) NOT NULL,
    command     VARCHAR(1000),
    exit_code   INT,
    stdout      TEXT,
    stderr      TEXT,
    timed_out   BOOLEAN,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
