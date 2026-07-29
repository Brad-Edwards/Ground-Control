-- Issue #250: historical MCP-created GitHub links used "#<number>", while the
-- backend sync contract parses the canonical positive decimal identifier.
--
-- Refuse ambiguous repairs. Deleting or merging one side would discard distinct
-- link identity and could orphan verification/audit references.
DO $$
DECLARE
    collision RECORD;
BEGIN
    SELECT
        legacy.requirement_id,
        legacy.artifact_type,
        SUBSTRING(legacy.artifact_identifier FROM 2) AS artifact_identifier,
        legacy.link_type
    INTO collision
    FROM traceability_link legacy
    JOIN traceability_link canonical
      ON canonical.requirement_id = legacy.requirement_id
     AND canonical.artifact_type = legacy.artifact_type
     AND canonical.artifact_identifier = SUBSTRING(legacy.artifact_identifier FROM 2)
     AND canonical.link_type = legacy.link_type
     AND canonical.id <> legacy.id
    WHERE legacy.artifact_type IN ('GITHUB_ISSUE', 'PULL_REQUEST')
      AND legacy.artifact_identifier ~ '^#[1-9][0-9]*$'
      AND SUBSTRING(legacy.artifact_identifier FROM 2)::NUMERIC <= 2147483647
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'canonical GitHub traceability identifier collision for requirement %, artifact type %, identifier %, link type %',
            collision.requirement_id,
            collision.artifact_type,
            collision.artifact_identifier,
            collision.link_type
            USING ERRCODE = '23505';
    END IF;
END
$$;

UPDATE traceability_link
SET artifact_identifier = SUBSTRING(artifact_identifier FROM 2),
    updated_at = NOW()
WHERE artifact_type IN ('GITHUB_ISSUE', 'PULL_REQUEST')
  AND artifact_identifier ~ '^#[1-9][0-9]*$'
  AND SUBSTRING(artifact_identifier FROM 2)::NUMERIC <= 2147483647;
