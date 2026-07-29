import {
  type NotificationVariant,
  useNotifications,
} from "@/components/ui/toast";
import { cn } from "@/lib/utils";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { Bell, X } from "lucide-react";

const dotClass: Record<NotificationVariant, string> = {
  success: "bg-success",
  error: "bg-danger",
  info: "bg-info",
};

function relativeTime(timestamp: number): string {
  const diffSec = Math.floor((Date.now() - timestamp) / 1000);
  if (diffSec < 60) return "just now";
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  return `${Math.floor(diffHr / 24)}d ago`;
}

/**
 * Transient notification surface (GC-Q015 clause (a)). Presents the in-memory notice history from
 * the toast hub — operation results and session notices — as a top-bar bell. It is deliberately
 * not a durable inbox: nothing is persisted and no backend aggregate backs it (preflight guardrail).
 */
export function NotificationCenter() {
  const { notifications, dismiss, clear } = useNotifications();
  const count = notifications.length;

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        className={cn(
          "relative inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground",
          "hover:bg-accent/50 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        )}
        aria-label={
          count > 0 ? `Notifications, ${count} recent` : "Notifications"
        }
      >
        <Bell className="h-4 w-4" aria-hidden />
        {count > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold text-primary-foreground">
            {count > 9 ? "9+" : count}
          </span>
        )}
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={6}
          className="w-80 rounded-md border border-border bg-card p-0 shadow-lg"
        >
          <div className="flex items-center justify-between border-b border-border px-3 py-2">
            <span className="text-sm font-medium text-foreground">
              Notifications
            </span>
            {count > 0 && (
              <button
                type="button"
                onClick={clear}
                className="text-xs text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Clear all
              </button>
            )}
          </div>
          {count === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground">
              No notifications.
            </p>
          ) : (
            <ul className="max-h-80 overflow-y-auto py-1">
              {notifications.map((n) => (
                <li
                  key={n.id}
                  className="flex items-start gap-2 px-3 py-2 hover:bg-accent/30"
                >
                  <span
                    className={cn(
                      "mt-1.5 h-2 w-2 shrink-0 rounded-full",
                      dotClass[n.variant],
                    )}
                    aria-hidden
                  />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm text-foreground">
                      {n.title}
                    </p>
                    {n.description && (
                      <p className="truncate text-xs text-muted-foreground">
                        {n.description}
                      </p>
                    )}
                    <p className="mt-0.5 text-[11px] text-muted-foreground">
                      {relativeTime(n.createdAt)}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => dismiss(n.id)}
                    className="rounded p-0.5 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    aria-label={`Dismiss ${n.title}`}
                  >
                    <X className="h-3 w-3" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
