-- IntaSend/M-Pesa payment relay (doc 14 section 6). One row per STK attempt
-- with an idempotent activation keyed on the provider invoice id, plus an
-- append-only subscription history. Keys are configuration, not schema, so
-- deployment swaps IntaSend test keys for live ones with environment variables.
ALTER TABLE subscriptions ADD COLUMN mpesa_transaction_id varchar(64);

CREATE TABLE payment_transactions (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tier              varchar(16) NOT NULL,
    amount            integer NOT NULL,
    currency          varchar(8) NOT NULL DEFAULT 'KES',
    phone_number      varchar(16) NOT NULL,
    provider          varchar(24) NOT NULL DEFAULT 'INTASEND',
    provider_ref      varchar(64),
    status            varchar(16) NOT NULL DEFAULT 'PENDING',
    failure_reason    text,
    client_request_id varchar(64),
    created_at        timestamp with time zone NOT NULL DEFAULT now(),
    updated_at        timestamp with time zone NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0
);
CREATE INDEX ix_payment_tx_user ON payment_transactions (user_id, created_at);
CREATE UNIQUE INDEX uq_payment_tx_provider_ref ON payment_transactions (provider_ref);
CREATE UNIQUE INDEX uq_payment_tx_client_request ON payment_transactions (user_id, client_request_id);

CREATE TABLE subscription_history (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    action         varchar(16) NOT NULL,
    tier           varchar(16) NOT NULL,
    amount         integer NOT NULL DEFAULT 0,
    transaction_id varchar(64),
    created_at     timestamp with time zone NOT NULL DEFAULT now()
);
CREATE INDEX ix_subscription_history_user ON subscription_history (user_id, created_at);
