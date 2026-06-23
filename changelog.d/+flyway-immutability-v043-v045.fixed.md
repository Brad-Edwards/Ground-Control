Revert the in-place edits to already-applied Flyway migrations `V043` and `V045`.
Commit `0b43d0a8` ("Align FAIR and NIST source semantics") rewrote the data seeded
by these migrations *and* added a forward migration (`V138`) that performs the same
realignment. Because `V043`/`V045` had already been applied to the production
database, editing them changed their checksums and broke Flyway validation on
deploy (`Migration checksum mismatch for version 043/045`), crashing startup. A
fresh CI database applies everything cleanly, so the smoke test never caught it.
`V138` already carries the realignment forward, so restoring `V043`/`V045` to their
original content loses nothing and lets existing databases validate again.
