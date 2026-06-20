-- Issue #1104: MCP tool usage telemetry event table.
-- Append-only operational data; no Envers audit table (rows are never mutated).
-- Captured fields are the closed event shape from the preflight: tool, action,
-- outcome, duration_ms, project, and ts. id and created_at are server-generated.

CREATE TABLE mcp_tool_event (
    id            UUID         PRIMARY KEY,
    tool          VARCHAR(200) NOT NULL,
    action        VARCHAR(200),
    outcome       VARCHAR(100) NOT NULL,
    duration_ms   BIGINT       NOT NULL CHECK (duration_ms >= 0),
    project       VARCHAR(200),
    event_ts      TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Primary aggregation access pattern: time-window query.
CREATE INDEX idx_mcp_tool_event_ts ON mcp_tool_event (event_ts);

-- Composite index for per-tool aggregation inside a time window.
CREATE INDEX idx_mcp_tool_event_ts_tool ON mcp_tool_event (event_ts, tool);
