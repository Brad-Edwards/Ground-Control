-- GC-RSCH-N012 / ADR-080 §2 (#1006). A methodology alternative that was rejected
-- in favour of the contract's active selection. The "why" stays in the rationale
-- ledger: rationale_entry_id references a METHODOLOGY_CHOICE rationale entry for
-- the same run. A rejected alternative not present in the backend catalog is
-- marked external with a bounded method_key/profile_version label only.
CREATE TABLE methodology_requirements_contract_rejected_alternative (
    id                 UUID         PRIMARY KEY,
    contract_id        UUID         NOT NULL REFERENCES methodology_requirements_contract(id),
    rationale_entry_id UUID         REFERENCES research_run_rationale_entry(id),
    method_key         VARCHAR(200) NOT NULL,
    profile_version    VARCHAR(100),
    external           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_contract_rejected_alternative_contract
    ON methodology_requirements_contract_rejected_alternative (contract_id);
