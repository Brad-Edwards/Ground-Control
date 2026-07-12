import { ProjectSwitcher } from "@/components/project-switcher";
import { cn } from "@/lib/utils";
import { LogOut, Rocket } from "lucide-react";
import {
  Link,
  NavLink,
  Outlet,
  useLocation,
  useParams,
} from "react-router-dom";

function NavItem({
  to,
  children,
  end,
}: {
  to: string;
  children: React.ReactNode;
  end?: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          "px-3 py-1.5 rounded-md text-sm font-medium transition-colors",
          isActive
            ? "bg-accent text-accent-foreground"
            : "text-muted-foreground hover:text-foreground hover:bg-accent/50",
        )
      }
    >
      {children}
    </NavLink>
  );
}

/**
 * Sign-out control wired to ADR-037's browser session chain. POSTs to {@code /logout} with the
 * CSRF token cookie echoed via the {@code X-XSRF-TOKEN} header (Spring's double-submit-cookie
 * contract). On a 204 success the server has invalidated the session; we then redirect to
 * {@code /login} so the user lands on the form-login screen. A non-204 response stays on the
 * page — there is no destructive state to roll back, and the on-page error surface is the most
 * useful diagnostic for an unexpected condition.
 */
function SignOutButton() {
  const handleSignOut = async () => {
    const csrfCookie = document.cookie
      .split(";")
      .map((entry) => entry.trim())
      .find((entry) => entry.startsWith("XSRF-TOKEN="));
    const headers: Record<string, string> = {};
    if (csrfCookie) {
      headers["X-XSRF-TOKEN"] = decodeURIComponent(
        csrfCookie.slice("XSRF-TOKEN=".length),
      );
    }
    const response = await fetch("/logout", {
      method: "POST",
      credentials: "same-origin",
      headers,
    });
    if (response.ok) {
      window.location.assign("/login");
    }
  };

  return (
    <button
      type="button"
      onClick={handleSignOut}
      className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm text-muted-foreground hover:bg-accent/50 hover:text-foreground"
      aria-label="Sign out"
    >
      <LogOut className="h-4 w-4" />
      <span>Sign out</span>
    </button>
  );
}

export function AppLayout() {
  const { projectId } = useParams<{ projectId: string }>();
  const location = useLocation();
  const isFullBleed = location.pathname.endsWith("/graph");

  const base = projectId ? `/p/${projectId}` : "";

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex min-h-14 max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2">
          <Link
            to={projectId ? `${base}/` : "/"}
            className="flex items-center gap-2 font-semibold"
          >
            <Rocket className="h-5 w-5 text-primary" />
            <span>Ground Control</span>
          </Link>

          <nav className="flex flex-wrap items-center gap-1">
            {projectId && (
              <>
                <NavItem to={`${base}/`} end>
                  Dashboard
                </NavItem>
                <NavItem to={`${base}/requirements`}>Requirements</NavItem>
                <NavItem to={`${base}/traceability-matrix`}>
                  Traceability Matrix
                </NavItem>
                <NavItem to={`${base}/test-runs`}>Test Runs</NavItem>
                <NavItem to={`${base}/graph`}>Graph</NavItem>
                <NavItem to={`${base}/analysis`}>Analysis</NavItem>
                <NavItem to={`${base}/workflow-runs`}>Workflow Runs</NavItem>
              </>
            )}
            <NavItem to="/projects">Projects</NavItem>
            {projectId && <NavItem to={`${base}/admin`}>Admin</NavItem>}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            {projectId && <ProjectSwitcher />}
            <SignOutButton />
          </div>
        </div>
      </header>

      <main className={cn(isFullBleed ? "" : "mx-auto max-w-7xl px-4 py-6")}>
        <Outlet />
      </main>
    </div>
  );
}
