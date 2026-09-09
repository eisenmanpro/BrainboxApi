-- ============================================================
-- BrainboxApi V5: idempotency records
-- Contract: docs/backend_contracts/11_... §8.3/§8.4 + ARCHITECTURE.md Appendix A
-- Caches one response per client-supplied X-Idempotency-Key so replayed
-- offline-sync POSTs (doc 11 §2.2) return the original result instead of
-- executing twice. Body is JSON text; purged after expiry.
-- ============================================================

CREATE TABLE idempotency_records (
    id           uuid PRIMARY KEY,
    key_hash     varchar(64)  NOT NULL,
    method       varchar(8)   NOT NULL,
    path         varchar(255) NOT NULL,
    status       integer      NOT NULL,
    content_type varchar(128),
    body         text,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    expires_at   timestamp with time zone NOT NULL
);

CREATE UNIQUE INDEX uq_idempotency_key ON idempotency_records (key_hash);
CREATE INDEX ix_idempotency_expiry ON idempotency_records (expires_at);
