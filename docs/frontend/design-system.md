# Console Design System

The construction material for console pages (GC-Q015). New workspaces compose
from these primitives and semantic tokens instead of hand-rolling UI. The
information architecture and rationale live in
[Console Information Architecture and Design-System Foundations](../../architecture/design/console-ia-design-system.md);
this page is the day-to-day usage reference.

## Design tokens

Colour, spacing, and radius come from the Tailwind `@theme` block in
`frontend/src/main.css`. Express state through **semantic tokens**, never raw
Tailwind colour classes, and always pair colour with a text or icon cue so
meaning never rests on colour alone.

| Token | Role |
|-------|------|
| `background` / `foreground` | App canvas and primary text |
| `card` / `card-foreground` | Bounded content surfaces |
| `border` / `input` | Dividers, table boundaries, form controls |
| `primary` | Main action, selected navigation |
| `accent` | Hover / selected low-emphasis surface |
| `info` | Neutral progress, running state, informational notices |
| `success` | Passed, merged, completed, active |
| `warning` | Pending, ready for review, stale, needs attention |
| `danger` | Failed, destructive, denied |
| `evidence` | Assurance-specific highlights |
| `ring` | Keyboard focus indicator |

Every state foreground is verified for WCAG 2.1 AA (≥ 4.5:1) against both the
background and the card surface. Tinted fills use the same hue at low alpha
(for example `bg-success/15 text-success`).

## Components

All live under `frontend/src/components/ui/` (plus the shell under
`components/layout/`). Prefer these over new page-local variants; promote a new
shared primitive only once there are three real call sites.

| Component | Use |
|-----------|-----|
| `AppShell` | Authenticated frame: top bar, grouped nav rail (drawer on small screens), full-bleed exception for the graph |
| `UserMenu` | Signed-in principal, compatibility role projection, admin affordance (gated on the server `canAdminister` hint), sign-out |
| `NotificationCenter` | Transient, in-memory notice surface (operation + session notices); not a durable inbox |
| `ProjectSwitcher` | Project context selection; preserves the workspace route key, never entity ids, across projects |
| `Badge` / `StatusBadge` / `PriorityBadge` / `TypeBadge` | Typed state display via semantic variants |
| `Button` | Actions; defaults to `type="button"`, always carries a visible focus ring |
| `MetricCard` | Numeric summary tile; renders as a real button when it drills down |
| `PageHeader` | The single H1 per route, with count and action slots |
| `EmptyState` | Meaningful absence with an optional next action |
| `ErrorPanel` | API/authorization errors; renders a 403 in place as unauthorized, never as route hiding |
| `LoadingState` / `Skeleton` | Container-sized spinner / fixed-height placeholders |
| `Modal` / `ConfirmDialog` / `SlidePanel` / `FormField` | Blocking decisions, destructive confirmation, detail panels, form fields |

## Interaction patterns

- **Tables** carry a visible title, count, and loading/empty/error states.
  Sortable columns use `aria-sort` and keyboard-focusable controls; icon-only
  row actions carry accessible names; destructive actions confirm.
- **Forms** use `FormField` labels, helper text, and validation, show pending
  state on submit, and route mutations through the CSRF-aware fetch wrappers.
  Actor/authorization is never taken from client-supplied fields.
- **Panels** trap focus while open and restore it to the trigger on close
  (Radix Dialog / SlidePanel handle this).
- **Empty/Error/Loading** states keep stable dimensions so content does not
  shift. A 401 redirects to `/login`; a 403 renders in place.

## Authenticated-session UX

The login bundle stays a separate anonymous asset graph (ADR-037). The
authenticated shell reads the current principal from `GET /api/v1/session`
(`useSession`): display name, a compatibility role projection, and a
`canAdminister` presentation hint. Authorization is always enforced server-side
by `ApiPathMatrix` and the service layer; the hint only shapes affordances.

- Sign-out POSTs to `/logout` echoing `XSRF-TOKEN` as `X-XSRF-TOKEN`.
- An XHR 401 redirects to `/login?expired=1`; the login bundle shows a
  session-expired notice from that fixed, non-secret flag.
- The shared query client never retries 401/403 responses.

## Accessibility

Keyboard navigation for nav groups, switcher, menus, modals, and tables;
visible AA-contrast focus rings; one H1 and semantic landmarks per route;
`aria-sort` on sortable columns; accessible names on icon-only controls; status
conveyed by text/icon in addition to colour. Reusable components carry component
tests under their `__tests__` directories.
