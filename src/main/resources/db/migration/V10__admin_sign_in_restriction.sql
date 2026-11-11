-- N4: the admin/HoI sign-in restriction switch.
--
-- Owner brief (docs/NEW/findings2.md item 4): a switch for school administrators,
-- open by default, that stops users signing in as admins/HoI; when the
-- restriction is on only the first administrator can turn it back off, and the
-- server answers with "contact the school admin for more information".
--
-- admin_sign_in_open is positive-named and defaults TRUE, mirroring
-- registration_open: an absent row means open, so "open by default" needs no
-- backfill. admin_owner_id pins "the first administrator" once (earliest active
-- ICT_ADMIN of the school) so the unlock right stays stable even when accounts
-- are added or removed later.
ALTER TABLE school_system_settings ADD COLUMN IF NOT EXISTS admin_sign_in_open boolean NOT NULL DEFAULT TRUE;
ALTER TABLE school_system_settings ADD COLUMN IF NOT EXISTS admin_owner_id uuid REFERENCES users (id) ON DELETE SET NULL;
