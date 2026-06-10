### Fixed: MCP test regressions from FAIR refactor and an audit-test self-defeat

- `gc_risk_scenario`'s FAIR-CRST rename in #720 removed the
  `threat_source` → `threatSource` and `threat_event` → `threatEvent`
  entries from `TO_CAMEL` in `mcp/ground-control/lib.js`. `gc_threat_model`
  still uses those snake_case field names on its public surface (per ADR-034);
  Jackson was silently dropping the fields on the wire so threat models
  created via MCP shipped without the threat source or event. Restored
  both mappings.
- The `gcAuditZodShape` "preserves every backend create body field through
  Zod parse" test built its input without `phases`, then asserted `phases`
  was in the parsed output. Zod drops absent optional fields from the
  parsed object by design, so the test was self-defeating. Reshaped the
  input to include every field the assertion exercises.
