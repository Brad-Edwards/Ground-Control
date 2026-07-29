import { useSession } from "@/hooks/use-session";
import { logout } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { LogOut, ShieldCheck, User } from "lucide-react";

/** Present a compatibility role authority (ROLE_ADMIN) as a human label during the ADR-085 migration. */
function roleLabel(authority: string): string {
  const bare = authority.startsWith("ROLE_") ? authority.slice(5) : authority;
  return bare.charAt(0) + bare.slice(1).toLowerCase();
}

/**
 * Authenticated-principal menu (GC-Q015 clause (a)). Renders the signed-in display name, a
 * compatibility role projection, and sign-out. Admin affordances are gated on the server-derived
 * {@code canAdminister} hint — presentation only; {@code ApiPathMatrix} remains the enforcement.
 */
export function UserMenu() {
  const { data: session, isLoading } = useSession();

  const displayName = session?.displayName ?? "Account";

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        className={cn(
          "inline-flex items-center gap-2 rounded-md px-2 py-1.5 text-sm text-foreground",
          "hover:bg-accent/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        )}
        aria-label="Account menu"
      >
        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-accent text-accent-foreground">
          <User className="h-4 w-4" aria-hidden />
        </span>
        <span className="hidden max-w-[10rem] truncate sm:inline">
          {isLoading ? "…" : displayName}
        </span>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="min-w-[220px] rounded-md border border-border bg-card p-1 shadow-lg"
        >
          <div className="px-3 py-2">
            <p className="truncate text-sm font-medium text-foreground">
              {displayName}
            </p>
            {session?.roles && session.roles.length > 0 && (
              <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
                {session.canAdminister && (
                  <ShieldCheck className="h-3 w-3 text-info" aria-hidden />
                )}
                {session.roles.map(roleLabel).join(" · ")}
              </p>
            )}
          </div>
          <DropdownMenu.Separator className="my-1 h-px bg-border" />
          <DropdownMenu.Item
            className="flex cursor-pointer items-center gap-2 rounded-sm px-3 py-2 text-sm text-foreground outline-none hover:bg-accent focus-visible:bg-accent"
            onSelect={() => {
              void logout();
            }}
          >
            <LogOut className="h-4 w-4" aria-hidden />
            Sign out
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
