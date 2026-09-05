# Design records

Two directories sit beside the ADRs, and neither is a current contract.

`architecture/design/` holds long-form design specifications. It is empty: the
console information-architecture specification that lived here described the
React console removed by the #1500 re-platform, and was deleted with its subject.

`architecture/notes/` holds point-in-time notes, most of them written by the
`/implement` architecture preflight before a change was implemented
([ADR-021](../adrs/021-gated-agentic-development-loop.md)). Each note records the
guardrails that applied to one issue on one day. It is evidence of what was
decided then, not a description of the system now, and many notes discuss
subsystems the re-platform removed.

For the current picture, read these instead:

| Question | Reference |
|----------|-----------|
| What runs, and where is the trust boundary? | [`docs/architecture/ARCHITECTURE.md`](../../docs/architecture/ARCHITECTURE.md) |
| Why is a decision the way it is? | [`architecture/adrs/`](../adrs/) |
| How does the gated workflow operate? | [`docs/DEVELOPMENT_WORKFLOW.md`](../../docs/DEVELOPMENT_WORKFLOW.md) |
| What does a requirement look like? | [`docs/requirements/`](../../docs/requirements/) |

The ADR index at [`architecture/adrs/README.md`](../adrs/README.md) marks which
decisions the re-platform superseded.
