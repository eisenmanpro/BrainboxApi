-- Email verification for staff signup (gaps.md items 2/3/4) and the signup directory.
--
-- teachers must confirm an emailed code before the account can be used. The two columns are
-- separate on purpose: email_verified_at records the fact, email_verification_required marks the
-- accounts that must supply it. Existing rows keep required=false, so no shipped account is
-- locked out by this migration (it is a new gate for new signups only).

ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at timestamp with time zone;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verification_required boolean NOT NULL DEFAULT false;

-- Lookup index for login/recovery by email. Uniqueness is enforced by the service on the
-- normalised (lower-cased) address; legacy rows may predate that rule.
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);

CREATE TABLE IF NOT EXISTS email_verification_challenges (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    email varchar(255) NOT NULL,
    code_hash varchar(64) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    consumed boolean NOT NULL DEFAULT false,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_evc_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_evc_user_created
    ON email_verification_challenges (user_id, created_at);
