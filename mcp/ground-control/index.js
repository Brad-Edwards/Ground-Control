#!/usr/bin/env node
// Ground Control MCP Server entry point.
//
// This file exists to establish one ordering contract: the environment is bound
// before the server runtime evaluates (issue #1562).
//
// `<launch directory>/.env` is the only source of Ground Control's own
// configuration and credentials. No machine-level or user-level file is
// consulted, and no owned variable falls back to whatever the launcher passed
// down — a launcher's environment must not be able to decide which tools work,
// or silently supply a credential to a repository that deliberately has none.
// If a variable a tool needs is absent, the tool refuses and names the variable
// and this file; the operator fixes the `.env` and restarts the server.
// `.env.example` documents every variable, and lib/server-env.js inventories
// them.
//
// ESM hoists static imports, so a module this file imported statically would
// evaluate before the loader below ran — which is how two review-size defaults
// came to permanently miss a value that lived only in `.env`. The runtime is
// therefore imported dynamically, after the binding. Keep the only static
// import here the leaf loader; server-bootstrap-order.test.js enforces that.

import { loadServerEnv } from "./lib/server-env.js";

loadServerEnv(process.env);

await import("./server-runtime.js");
