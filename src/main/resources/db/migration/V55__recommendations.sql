-- ============================================================
-- BrainboxApi V55: recommendation interaction telemetry
-- docs/ongoing/api_recommendations_changes.md. The learner rail treats the
-- server as the source of truth; these rows feed collaborative and trending
-- signals. De-duplicated per (user, post, timestamp) for best-effort replay.
-- ============================================================

CREATE TABLE recommendation_interactions (
    id                 uuid PRIMARY KEY,
    user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id            uuid NOT NULL REFERENCES learning_posts (id) ON DELETE CASCADE,
    interaction_type   varchar(32) NOT NULL,
    time_spent_seconds integer,
    interaction_score  double precision NOT NULL DEFAULT 0,
    occurred_at        timestamp with time zone NOT NULL,
    school_id          uuid REFERENCES schools (id) ON DELETE SET NULL,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone NOT NULL DEFAULT now(),
    version            bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_recommendation_interaction UNIQUE (user_id, post_id, occurred_at)
);

CREATE INDEX ix_recommendation_interactions_school ON recommendation_interactions (school_id, occurred_at);
CREATE INDEX ix_recommendation_interactions_user ON recommendation_interactions (user_id, post_id);
