package com.keplerops.groundcontrol.domain.requirements.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Allocates the next available UID for a given per-project prefix using an
 * advisory transaction lock so concurrent callers never collide.
 *
 * <p>The lock key is a stable hash of {@code (projectId, normalizedPrefix)} cast to int,
 * combined with a constant namespace key (GCUID = 0x47435549) as the first advisory key
 * argument. {@code pg_advisory_xact_lock} auto-releases on COMMIT/ROLLBACK — no manual
 * unlock needed. This mirrors the pattern in UserAdminService for admin-mutation serialisation.
 *
 * <p>Runs inside the caller's transaction (no @Transactional annotation here).
 */
@Service
public class RequirementUidAllocator {

    /** First advisory lock key — namespace constant: 0x47435549 = 'GCUI' */
    private static final int UID_LOCK_NAMESPACE = 0x47435549; // GCUID

    /** Postgres advisory lock SQL: takes two int4 args, scoped to the current transaction. */
    private static final String ADVISORY_LOCK_SQL = "SELECT pg_advisory_xact_lock(?, ?)";

    /**
     * Valid prefix pattern: uppercase letters/digits with optional hyphen-separated segments.
     * Callers pass a raw prefix; it is normalized to uppercase before validation. Possessive
     * quantifiers ({@code *+}, {@code ++}) make the match linear-time with no backtracking, so a
     * pathological input cannot trigger catastrophic backtracking / stack overflow (Sonar S5998).
     */
    private static final Pattern VALID_PREFIX = Pattern.compile("^[A-Z][A-Z0-9]*+(?:-[A-Z0-9]++)*+$");

    private final RequirementRepository requirementRepository;
    private final JdbcTemplate jdbcTemplate;

    public RequirementUidAllocator(RequirementRepository requirementRepository, JdbcTemplate jdbcTemplate) {
        this.requirementRepository = requirementRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Allocate the next UID for the given prefix within the project.
     *
     * <p>Steps:
     * <ol>
     *   <li>Normalize prefix to uppercase.</li>
     *   <li>Validate against {@code ^[A-Z][A-Z0-9]*(-[A-Z0-9]+)*$}.</li>
     *   <li>Acquire a pg_advisory_xact_lock keyed on (UID_LOCK_NAMESPACE, stableHash(projectId, prefix)).</li>
     *   <li>Read the maximum numeric suffix via native SQL on the requirement table.</li>
     *   <li>Return {@code prefix + "-" + (max + 1)}.</li>
     * </ol>
     *
     * <p>Archived rows are intentionally included in the max-suffix query so their UIDs
     * remain reserved and cannot be reissued.
     *
     * @param projectId the owning project
     * @param prefix    the raw prefix string (will be uppercased)
     * @return the allocated UID, e.g. {@code PLAT-006}
     * @throws DomainValidationException if the normalized prefix is invalid
     */
    public String allocate(UUID projectId, String prefix) {
        String normalizedPrefix = prefix.toUpperCase(Locale.ROOT);
        if (!VALID_PREFIX.matcher(normalizedPrefix).matches()) {
            throw new DomainValidationException(
                    "invalid_uid_prefix: '" + normalizedPrefix + "' does not match ^[A-Z][A-Z0-9]*(-[A-Z0-9]+)*$",
                    "invalid_uid_prefix",
                    java.util.Map.of("prefix", normalizedPrefix));
        }

        // Acquire advisory lock scoped to this project + prefix combination.
        int lockKey = stableHash(projectId, normalizedPrefix);
        jdbcTemplate.queryForList(ADVISORY_LOCK_SQL, UID_LOCK_NAMESPACE, lockKey);

        // Anchored pattern so PLAT != PLATFORM-001.
        String pattern = "^" + normalizedPrefix + "-[0-9]+$";
        long maxSuffix = requirementRepository.findMaxUidSuffix(projectId, pattern);

        return normalizedPrefix + "-" + (maxSuffix + 1);
    }

    /**
     * Compute a stable int hash of (projectId, normalizedPrefix) for use as
     * a pg_advisory_xact_lock key. Uses a simple but deterministic combination
     * that avoids collisions between similar prefixes.
     */
    static int stableHash(UUID projectId, String normalizedPrefix) {
        // Combine both halves of UUID with the prefix's own hashCode, folding with XOR
        // and an odd-multiplier to spread bits. Not cryptographic; purely advisory-lock
        // discriminator — collisions cause unnecessary serialisation, not correctness bugs.
        long lsb = projectId.getLeastSignificantBits();
        long msb = projectId.getMostSignificantBits();
        int projectHash = (int) (lsb ^ (lsb >>> 32)) ^ (int) (msb ^ (msb >>> 32));
        return projectHash * 1000003 ^ normalizedPrefix.hashCode();
    }
}
