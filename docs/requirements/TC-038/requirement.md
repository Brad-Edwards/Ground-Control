---
id: TC-038
title: "SSO and Authentication"
status: DRAFT
type: NON_FUNCTIONAL
priority: SHOULD
wave: 5
created_at: 2026-03-22T06:15:44.203056Z
updated_at: 2026-03-22T06:15:44.203056Z
---

# TC-038 — SSO and Authentication

## Statement

The system shall support authentication via: SAML 2.0 SSO, OAuth 2.0, OpenID Connect (OIDC), LDAP, and local username/password. The system shall support multi-factor authentication (MFA), SCIM user provisioning, and personal API tokens.

## Rationale

TestRail Enterprise supports SAML, OIDC, and SCIM. Kiwi TCMS supports OAuth, LDAP, and Kerberos. PractiTest supports SAML with SOC 2 and ISO 27001 compliance. Enterprise SSO is required for organizational adoption.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#768` (TC-038: SSO and Authentication)
