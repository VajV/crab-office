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

CREATE INDEX IF NOT EXISTS idx_agent_actions_room_id ON agent_actions(room_id);
CREATE INDEX IF NOT EXISTS idx_agent_actions_room_created_at ON agent_actions(room_id, created_at ASC);
