Add a `migration-immutability` policy check (`make policy`) that fails when a
Flyway migration already present on the released baseline (`origin/main`) is
modified or removed. Editing an applied migration changes its checksum and
crashes every database that already ran it on startup. This is the failure mode
behind the V043/V045 production incident, which a fresh-database smoke test
cannot catch. New forward migrations are exempt.
