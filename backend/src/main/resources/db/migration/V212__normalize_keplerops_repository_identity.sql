-- Issue #1500 (GC-P026): active traceability surfaces must name the canonical
-- autarchy-ai/Ground-Control repository. Historical GitHub issue/PR traceability links created
-- before the KeplerOps -> autarchy-ai owner move still carry the stale identity in both the
-- artifact identifier and the artifact URL. GitHub preserves issue/PR numbers across the
-- transfer, so only the owner/repo prefix changes and the numeric reference is unchanged.
--
-- Idempotent: REPLACE is a no-op on already-normalized rows, so re-application is safe.
UPDATE traceability_link
SET artifact_identifier = REPLACE(artifact_identifier, 'KeplerOps/Ground-Control', 'autarchy-ai/Ground-Control'),
    artifact_url = REPLACE(artifact_url, 'KeplerOps/Ground-Control', 'autarchy-ai/Ground-Control'),
    updated_at = NOW()
WHERE artifact_identifier LIKE 'KeplerOps/Ground-Control%'
   OR artifact_url LIKE '%KeplerOps/Ground-Control%';
