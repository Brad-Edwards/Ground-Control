-- Rename the four FAIR-CRST scoping axes (live + audit) and drop the vulnerability free-text field.
ALTER TABLE risk_scenario RENAME COLUMN threat_source   TO threat;
ALTER TABLE risk_scenario RENAME COLUMN threat_event    TO method;
ALTER TABLE risk_scenario RENAME COLUMN affected_object TO asset;
ALTER TABLE risk_scenario RENAME COLUMN consequence     TO effect;
ALTER TABLE risk_scenario DROP COLUMN IF EXISTS vulnerability;

ALTER TABLE risk_scenario_audit RENAME COLUMN threat_source   TO threat;
ALTER TABLE risk_scenario_audit RENAME COLUMN threat_event    TO method;
ALTER TABLE risk_scenario_audit RENAME COLUMN affected_object TO asset;
ALTER TABLE risk_scenario_audit RENAME COLUMN consequence     TO effect;
ALTER TABLE risk_scenario_audit DROP COLUMN IF EXISTS vulnerability;
