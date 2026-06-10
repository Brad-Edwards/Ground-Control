### Fixed: SonarCloud cycle-2 single-return refactor of NistAssessmentService.deriveOverall

Pre-push SonarCloud cycle 2 on the GC-T014 PR (#1054) flagged
`NistAssessmentService.deriveOverall` for having four return statements
where the rule allows three (java:S1142). Rewrote the
persisted-overall / analyst-supplied / Table G-5-derived / not-derivable
chain as an assign-then-return sequence so the method has one exit
point. No behaviour change.
