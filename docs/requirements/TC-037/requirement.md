---
id: TC-037
title: "Role-Based Access Control"
status: DRAFT
type: NON_FUNCTIONAL
priority: MUST
wave: 5
created_at: 2026-03-22T06:15:40.269233Z
updated_at: 2026-03-22T06:15:40.269233Z
---

# TC-037 — Role-Based Access Control

## Statement

The system shall support role-based access control with: built-in roles (Admin, Manager, Tester, Viewer/Stakeholder), custom role creation, project-level access control, granular permissions per entity type (add/change/delete/view), and group-based permission management.

## Rationale

TestRail, PractiTest, qTest, Kiwi TCMS, and Azure Test Plans all provide RBAC. Kiwi TCMS supports granular per-entity permissions. TestRail supports custom roles and group-based management. RBAC is prerequisite for multi-user deployments.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#767` (TC-037: Role-Based Access Control)
