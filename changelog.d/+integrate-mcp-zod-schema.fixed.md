### Fixed

- `gc_integration_manager` MCP tool: re-registered via `server.tool(name, desc, zodShape, handler)` so the SDK's `safeParseAsync` path resolves. The prior registration used `server.registerTool({inputSchema: <raw JSON Schema>})`, which passes the registration gate but crashes every invocation with `v3Schema.safeParseAsync is not a function`. Added an `McpServer` + `Client` + `InMemoryTransport` regression test in `gc-integrate.test.js` so any future schema-shape regression fails in CI instead of on a real call.
