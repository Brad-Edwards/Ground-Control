Resolve 13 SonarCloud new-code findings blocking the dev → main release gate:
extract a `paths` field constant and replace a `for`-counter mutation with a
`while` loop in `DerivationService`; route internal lookups through a private
helper instead of self-invoking the transactional `getById` in
`MethodologyProfileService`, and collapse the 9-parameter `seedIfMissing` into a
`MethodologyProfileSeed` parameter object; hoist throwing setup calls out of
`assertThatThrownBy` lambdas, chain duplicate assertions, drop redundant `eq(...)`
matchers in the affected tests; and convert a bare `TODO` in `AuditService` into a
tracked known-limitation note (issue #1212).
