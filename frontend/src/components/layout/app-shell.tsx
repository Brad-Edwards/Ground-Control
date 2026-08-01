import { ProjectSwitcher } from "@/components/project-switcher";
import { NotificationCenter } from "@/components/ui/notification-center";
import { UserMenu } from "@/components/ui/user-menu";
import { useSession } from "@/hooks/use-session";
import { cn } from "@/lib/utils";
import * as Dialog from "@radix-ui/react-dialog";
import {
  Activity,
  FileText,
  FlaskConical,
  FolderOpen,
  LayoutDashboard,
  LineChart,
  Link2,
  type LucideIcon,
  Menu,
  Rocket,
  Settings,
  Share2,
  Workflow,
} from "lucide-react";
import { useState } from "react";
import { Link, NavLink, Outlet, useLocation, useParams } from "react-router";

interface NavItemDef {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
  /** Gated on the server-derived canAdminister hint — presentation only, backend still enforces. */
  adminOnly?: boolean;
}

interface NavGroupDef {
  label: string;
  items: NavItemDef[];
}

/**
 * Grouped project navigation (GC-Q015 clause (a), console-ia-design-system.md § Navigation Groups).
 * The ADR-089-retired GRC/Assurance workspaces are intentionally absent — they are not migration
 * targets. Future Workflow Reporting / Identity Administration surfaces are not scaffolded here
 * because no route backs them yet.
 */
function projectNavGroups(base: string): NavGroupDef[] {
  return [
    {
      label: "Overview",
      items: [
        {
          to: `${base}/`,
          label: "Dashboard",
          icon: LayoutDashboard,
          end: true,
        },
      ],
    },
    {
      label: "Requirements",
      items: [
        { to: `${base}/requirements`, label: "Requirements", icon: FileText },
      ],
    },
    {
      label: "Traceability & Verification",
      items: [
        {
          to: `${base}/traceability-matrix`,
          label: "Traceability Matrix",
          icon: Link2,
        },
        { to: `${base}/test-runs`, label: "Test Runs", icon: FlaskConical },
      ],
    },
    {
      label: "Graph & Analysis",
      items: [
        { to: `${base}/graph`, label: "Graph", icon: Share2 },
        { to: `${base}/analysis`, label: "Analysis", icon: LineChart },
      ],
    },
    {
      label: "Workflow",
      items: [
        { to: `${base}/activity`, label: "Live Activity", icon: Activity },
        { to: `${base}/workflow-runs`, label: "Workflow Runs", icon: Workflow },
      ],
    },
    {
      label: "Administration",
      items: [
        {
          to: `${base}/admin`,
          label: "Admin",
          icon: Settings,
          adminOnly: true,
        },
      ],
    },
  ];
}

function NavItem({
  item,
  onNavigate,
}: {
  item: NavItemDef;
  onNavigate?: () => void;
}) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          "flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          isActive
            ? "bg-accent text-accent-foreground"
            : "text-muted-foreground hover:bg-accent/50 hover:text-foreground",
        )
      }
    >
      <Icon className="h-4 w-4 shrink-0" aria-hidden />
      <span className="truncate">{item.label}</span>
    </NavLink>
  );
}

function RailNav({
  base,
  canAdminister,
  onNavigate,
}: {
  base: string;
  canAdminister: boolean;
  onNavigate?: () => void;
}) {
  const groups = projectNavGroups(base);
  return (
    <nav aria-label="Primary" className="flex flex-col gap-5">
      {groups.map((group) => {
        const items = group.items.filter(
          (item) => !item.adminOnly || canAdminister,
        );
        if (items.length === 0) return null;
        return (
          <div key={group.label} className="flex flex-col gap-1">
            <p className="px-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground/70">
              {group.label}
            </p>
            {items.map((item) => (
              <NavItem key={item.to} item={item} onNavigate={onNavigate} />
            ))}
          </div>
        );
      })}
    </nav>
  );
}

export function AppShell() {
  const { projectId } = useParams<{ projectId: string }>();
  const location = useLocation();
  const { data: session } = useSession();
  const [drawerOpen, setDrawerOpen] = useState(false);

  const isFullBleed = location.pathname.endsWith("/graph");
  const base = projectId ? `/p/${projectId}` : "";
  const canAdminister = session?.canAdminister ?? false;

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="sticky top-0 z-30 border-b border-border bg-card">
        <div className="flex h-14 items-center gap-3 px-4">
          {projectId && (
            <Dialog.Root open={drawerOpen} onOpenChange={setDrawerOpen}>
              <Dialog.Trigger
                className="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent/50 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring lg:hidden"
                aria-label="Open navigation menu"
              >
                <Menu className="h-5 w-5" aria-hidden />
              </Dialog.Trigger>
              <Dialog.Portal>
                <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50 lg:hidden" />
                <Dialog.Content
                  aria-describedby={undefined}
                  className="fixed inset-y-0 left-0 z-50 w-64 overflow-y-auto border-r border-border bg-card p-4 lg:hidden"
                >
                  <Dialog.Title className="sr-only">Navigation</Dialog.Title>
                  <RailNav
                    base={base}
                    canAdminister={canAdminister}
                    onNavigate={() => setDrawerOpen(false)}
                  />
                </Dialog.Content>
              </Dialog.Portal>
            </Dialog.Root>
          )}

          <Link
            to={projectId ? `${base}/` : "/"}
            className="flex items-center gap-2 font-semibold text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Rocket className="h-5 w-5 text-primary" aria-hidden />
            <span>Ground Control</span>
          </Link>

          <div className="ml-auto flex items-center gap-2">
            {projectId && <ProjectSwitcher />}
            <NavLink
              to="/projects"
              className={({ isActive }) =>
                cn(
                  "hidden items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm font-medium sm:inline-flex",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  isActive
                    ? "bg-accent text-accent-foreground"
                    : "text-muted-foreground hover:bg-accent/50 hover:text-foreground",
                )
              }
            >
              <FolderOpen className="h-4 w-4" aria-hidden />
              Projects
            </NavLink>
            <NotificationCenter />
            <UserMenu />
          </div>
        </div>
      </header>

      <div className="flex flex-1">
        {projectId && (
          <aside className="hidden w-60 shrink-0 border-r border-border bg-card px-3 py-6 lg:block">
            <RailNav base={base} canAdminister={canAdminister} />
          </aside>
        )}

        <main className="min-w-0 flex-1">
          <div className={cn(isFullBleed ? "" : "mx-auto max-w-7xl px-4 py-6")}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
}
