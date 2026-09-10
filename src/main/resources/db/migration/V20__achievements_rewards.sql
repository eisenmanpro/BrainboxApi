-- ============================================================
-- BrainboxApi V20: achievements, XP, badges and rewards
-- Contract: docs/backend_contracts/03_... §7/§8 + the Android AchievementsApi/models.
-- ============================================================

CREATE TABLE user_achievements (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    total_xp       integer NOT NULL DEFAULT 0,
    streak_freezes integer NOT NULL DEFAULT 0,
    grace_days     integer NOT NULL DEFAULT 0,
    created_at     timestamp with time zone NOT NULL DEFAULT now(),
    updated_at     timestamp with time zone NOT NULL DEFAULT now(),
    version        bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_achievements ON user_achievements (user_id);

CREATE TABLE xp_events (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    amount        integer NOT NULL,
    activity_type varchar(64) NOT NULL,
    created_at    timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX ix_xp_events_user ON xp_events (user_id, created_at);

CREATE TABLE badges (
    id          uuid PRIMARY KEY,
    title       varchar(128) NOT NULL,
    icon        varchar(64) NOT NULL,
    description varchar(255) NOT NULL DEFAULT '',
    tier        varchar(16),
    cbc_strand  varchar(32),
    is_secret   boolean NOT NULL DEFAULT FALSE,
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE user_badges (
    id        uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    badge_id  uuid NOT NULL REFERENCES badges (id) ON DELETE CASCADE,
    earned_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_badge ON user_badges (user_id, badge_id);

CREATE TABLE rewards (
    id          uuid PRIMARY KEY,
    title       varchar(160) NOT NULL,
    description text NOT NULL DEFAULT '',
    xp_cost     integer NOT NULL,
    icon        varchar(64) NOT NULL,
    type        varchar(32) NOT NULL,
    category    varchar(64) NOT NULL DEFAULT '',
    available   boolean NOT NULL DEFAULT TRUE,
    valid_until timestamp with time zone,
    image_url   varchar(512),
    created_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE user_rewards (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reward_id   uuid NOT NULL REFERENCES rewards (id) ON DELETE CASCADE,
    coupon_code varchar(32) NOT NULL,
    redeemed_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_reward ON user_rewards (user_id, reward_id);

INSERT INTO badges (id, title, icon, description, tier, cbc_strand) VALUES
 ('66666666-6666-6666-6666-000000000001', 'Early Bird', 'EB', 'Practise before 8am.', 'NOVICE', 'ORAL_EXPRESSION'),
 ('66666666-6666-6666-6666-000000000002', 'Quiz Master', 'QM', 'Score 80%+ in five quizzes.', 'DEVELOPING', 'LOGICAL_SEQUENCING'),
 ('66666666-6666-6666-6666-000000000003', 'Speed Demon', 'SD', 'Finish a quiz under half the time.', 'PROFICIENT', 'CRITICAL_THINKING'),
 ('66666666-6666-6666-6666-000000000004', 'Collaboration Star', 'CS', 'Help classmates in the doubt forum.', 'ADVANCED', 'COLLABORATION'),
 ('66666666-6666-6666-6666-000000000005', 'Creative Thinker', 'CT', 'Submit an outstanding CBC project.', 'MASTER', 'CREATIVITY'),
 ('66666666-6666-6666-6666-000000000006', 'Digital Explorer', 'DE', 'Complete ten learning posts.', 'NOVICE', 'DIGITAL_LITERACY'),
 ('66666666-6666-6666-6666-000000000007', 'Helper', 'HL', 'Answer five questions for peers.', 'DEVELOPING', 'COLLABORATION'),
 ('66666666-6666-6666-6666-000000000008', 'Contest Winner', 'CW', 'Win a BrainBox contest.', 'ADVANCED', 'CRITICAL_THINKING'),
 ('66666666-6666-6666-6666-000000000009', 'Perfect Week', 'PW', 'Practise every day for a week.', 'PROFICIENT', 'LOGICAL_SEQUENCING'),
 ('66666666-6666-6666-6666-000000000010', 'Top 10%', 'T1', 'Rank in the national top 10%.', 'MASTER', 'DIGITAL_LITERACY');

INSERT INTO rewards (id, title, description, xp_cost, icon, type, category) VALUES
 ('77777777-7777-7777-7777-000000000001', '1 Month Premium', 'Unlock all premium features for 30 days.', 5000, 'premium', 'PREMIUM_ACCESS', 'Premium'),
 ('77777777-7777-7777-7777-000000000002', 'Pizza Voucher', 'Ksh 500 voucher for Pizza Inn.', 10000, 'food', 'FOOD', 'Food'),
 ('77777777-7777-7777-7777-000000000003', 'Book Store Coupon', 'Ksh 1000 coupon for Text Book Centre.', 15000, 'education', 'EDUCATION', 'Education'),
 ('77777777-7777-7777-7777-000000000004', 'BrainBox Hoodie', 'Exclusive branded hoodie.', 50000, 'merchandise', 'MERCHANDISE', 'Merchandise');
