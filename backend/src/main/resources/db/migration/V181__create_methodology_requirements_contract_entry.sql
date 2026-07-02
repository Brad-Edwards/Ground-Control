-- GC-RSCH-F007 / ADR-080 §3 (#1006). One extracted entry within a methodology
-- requirements contract. Entries are written once with the contract (immutable
-- snapshot, not audited separately). entry_key is a stable, contract-unique key
-- that protocol planning references. statement is bounded free text; the backend
-- does not parse it for domain answers (ADR-080 §4).
CREATE TABLE methodology_requirements_contract_entry (
    id          UUID          PRIMARY KEY,
    contract_id UUID          NOT NULL REFERENCES methodology_requirements_contract(id),
    kind        VARCHAR(40)   NOT NULL,
    entry_key   VARCHAR(200)  NOT NULL,
    statement   VARCHAR(2000) NOT NULL,
    -- An OPEN_PROTOCOL_QUESTION that is not itself source-linked references the
    -- requirement/limit/non-claim entry (by entry_key, same contract) that raises it.
    references_entry_key VARCHAR(200),
    actor       VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_contract_entry_kind
        CHECK (kind IN ('REQUIREMENT', 'METHOD_LIMIT', 'NON_CLAIM', 'OPEN_PROTOCOL_QUESTION')),
    CONSTRAINT uq_contract_entry_key UNIQUE (contract_id, entry_key)
);

CREATE INDEX idx_contract_entry_contract
    ON methodology_requirements_contract_entry (contract_id);
