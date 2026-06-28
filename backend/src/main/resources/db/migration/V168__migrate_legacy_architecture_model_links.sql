-- GC-GRC-005: ARCHITECTURE_MODEL threat-model links now point at first-class
-- architecture_model_element rows through target_entity_id.
--
-- Older rows used target_type='ARCHITECTURE_MODEL' with only a free-text
-- target_identifier for C4, Structurizr, or other external diagram references.
-- After the promotion, those legacy rows would neither validate as internal
-- architecture-model links nor project to graph edges. Preserve their external
-- reference semantics by relabelling them as EXTERNAL and prefixing the original
-- identifier to avoid collisions with ordinary external links.

UPDATE threat_model_link
SET target_type = 'EXTERNAL',
    target_identifier = 'legacy-architecture-model:' || target_identifier,
    updated_at = NOW()
WHERE target_type = 'ARCHITECTURE_MODEL'
  AND target_entity_id IS NULL
  AND target_identifier IS NOT NULL;
