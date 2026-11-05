-- Current network of each tenant: one row per directed route (SPEC §3.1).
CREATE TABLE route (
    tenant_id   VARCHAR(64) NOT NULL,
    origin      CHAR(3)     NOT NULL,
    destination CHAR(3)     NOT NULL,
    type        VARCHAR(16) NOT NULL CHECK (type IN ('DIRECT', 'CONNECTING')),
    via         TEXT[]      NOT NULL DEFAULT '{}',
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, origin, destination),
    CHECK (origin <> destination)
);

-- Every sync attempt, whatever its outcome (SPEC §4, §6.1).
CREATE TABLE sync_run (
    id          UUID        PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'DEGRADED', 'REJECTED', 'FAILED')),
    source_used VARCHAR(64),
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    route_count INT         NOT NULL DEFAULT 0,
    added       INT         NOT NULL DEFAULT 0,
    removed     INT         NOT NULL DEFAULT 0,
    modified    INT         NOT NULL DEFAULT 0,
    issue_count INT         NOT NULL DEFAULT 0,
    error       TEXT
);
CREATE INDEX sync_run_tenant_started_idx ON sync_run (tenant_id, started_at DESC);

-- Change log, written in the same transaction as the routes it describes.
CREATE TABLE route_change (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    run_id      UUID        NOT NULL REFERENCES sync_run (id),
    change_type VARCHAR(16) NOT NULL CHECK (change_type IN ('ADDED', 'REMOVED', 'MODIFIED')),
    origin      CHAR(3)     NOT NULL,
    destination CHAR(3)     NOT NULL,
    old_type    VARCHAR(16),
    new_type    VARCHAR(16),
    old_via     TEXT[],
    new_via     TEXT[],
    detected_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX route_change_tenant_idx ON route_change (tenant_id, id DESC);
