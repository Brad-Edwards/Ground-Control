-- GC-T012: Add crosswalk_entries column to methodology_profile.
-- Stores a JSON list of CrosswalkEntry records mapping methodology-specific
-- terminology to the normalized risk concept vocabulary.
ALTER TABLE methodology_profile
    ADD COLUMN crosswalk_entries TEXT NULL;
