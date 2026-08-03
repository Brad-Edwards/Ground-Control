---
id: GC-P005
title: "Plugin Architecture"
status: ACTIVE
type: FUNCTIONAL
priority: SHOULD
wave: 4
created_at: 2026-03-13T23:15:36.043097Z
updated_at: 2026-04-09T03:25:24.512207Z
---

# GC-P005 — Plugin Architecture

## Statement

The system shall support a plugin architecture enabling runtime extension with new verifier adapters, export formats, analysis rules, and integration connectors without modifying core system code.

## Rationale

ADR-014 mandates pluggable verification. Extending this to a general plugin architecture enables the ecosystem to grow without core team bottleneck. Export formats and analysis rules are natural extension points.

## Traceability

- DOCUMENTS → ADR `ADR-022` (Content Pack Distribution Architecture)
- DOCUMENTS → ADR `ADR-023` (Plugin Architecture)
- IMPLEMENTS → CODE_FILE `domain/plugins/service/Plugin.java` (Plugin interface)
- IMPLEMENTS → CODE_FILE `domain/plugins/service/PluginDescriptor.java` (Plugin metadata record)
- IMPLEMENTS → CODE_FILE `domain/plugins/service/PluginRegistry.java` (Plugin registry service)
- IMPLEMENTS → CODE_FILE `domain/plugins/service/PluginInfo.java` (Plugin info projection record)
- IMPLEMENTS → CODE_FILE `domain/plugins/service/RegisterPluginCommand.java` (Register plugin command)
- IMPLEMENTS → CODE_FILE `domain/plugins/state/PluginType.java` (Plugin type enum)
- IMPLEMENTS → CODE_FILE `domain/plugins/state/PluginLifecycleState.java` (Plugin lifecycle state enum)
- IMPLEMENTS → CODE_FILE `domain/plugins/model/RegisteredPlugin.java` (Registered plugin JPA entity)
- IMPLEMENTS → CODE_FILE `domain/plugins/repository/RegisteredPluginRepository.java` (Registered plugin repository)
- IMPLEMENTS → CODE_FILE `api/plugins/PluginController.java` (Plugin REST controller)
- IMPLEMENTS → CODE_FILE `V051__create_registered_plugin.sql` (Flyway migration for registered_plugin table)
- TESTS → TEST `unit/domain/PluginRegistryTest.java` (Plugin registry unit tests)
- TESTS → TEST `unit/domain/PluginContractTest.java` (Plugin interface contract tests)
- TESTS → TEST `unit/api/PluginControllerTest.java` (Plugin controller WebMvc tests)
