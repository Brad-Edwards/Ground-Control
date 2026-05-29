### Fixed: SonarCloud cycle-1 refactor of NistAssessmentService

Pre-push SonarCloud cycle 1 on the GC-T014 PR (#1054) flagged eight code
smells in `NistAssessmentService.java` (cognitive complexity 29 vs 15
allowed on `toItem`, method length 118 vs 100 allowed, five duplicated
band string literals in the Table I-2 matrix, the duplicated
`"impact_level"` key string, a `p -> p.getIdentifier()` lambda where a
method reference fits, and a test lambda with two invocations that could
each throw). Decomposed `toItem` into `decodeInputs` / `deriveOverall` /
`resolveRisk` / `applyContextLimitations` helpers backed by record
carriers; extracted methodology-defined map keys into `KEY_*` / `OUT_*`
constants; replaced literal band strings in the matrix with
`NistLikelihoodBand` enum constants; switched to the
`Project::getIdentifier` method reference; hoisted `fairResult.getId()`
out of the `assertThatThrownBy` lambda. No behaviour change.
