### Fixed
- Further reduced cognitive complexity and return counts in `FairQuantitativeAnalysisService` by extracting `reconcileSecondaryMonetary`, `reconcileSecondaryScale`, `buildThreePoint`, `parseDoubleOrNull`, and `coalesce` helpers to satisfy SonarCloud code smell limits. (#1063)
