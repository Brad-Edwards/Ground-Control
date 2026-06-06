#!/usr/bin/env node
import { runPackSelftestCli } from "../../../tools/selftest-pack.mjs";

await runPackSelftestCli({ defaultPackId: "jvm-gradle" });
