-- GC-T012 / ADR-026 audit parity: add crosswalk_entries column to
-- methodology_profile_audit (Envers shadow table). Nullable per Envers convention;
-- the live table column is also NULL by default so existing rows are unaffected.
ALTER TABLE methodology_profile_audit
    ADD COLUMN crosswalk_entries TEXT NULL;
