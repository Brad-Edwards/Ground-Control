import { cn } from "@/lib/utils";
import * as Toast from "@radix-ui/react-toast";
import { X } from "lucide-react";
import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
} from "react";

export type NotificationVariant = "success" | "error" | "info";

interface NotificationInput {
  title: string;
  description?: string;
  variant: NotificationVariant;
}

interface ToastItem extends NotificationInput {
  id: number;
}

/** A notice retained in the transient in-memory history surfaced by the NotificationCenter. */
export interface NotificationRecord extends ToastItem {
  createdAt: number;
}

interface NotificationContextValue {
  /** Raise a transient notice: shows a toast and appends to the in-memory history. */
  notify: (item: NotificationInput) => void;
  /** Bounded, in-memory history of recent notices. Not persisted (ADR: shell UX only). */
  history: NotificationRecord[];
  dismiss: (id: number) => void;
  clear: () => void;
}

const NotificationContext = createContext<NotificationContextValue | null>(
  null,
);

/** Cap the in-memory notice history; this surface is transient, not a durable inbox. */
const HISTORY_LIMIT = 30;

let nextId = 0;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [history, setHistory] = useState<NotificationRecord[]>([]);

  const notify = useCallback((item: NotificationInput) => {
    const id = nextId++;
    setToasts((prev) => [...prev, { ...item, id }]);
    setHistory((prev) =>
      [{ ...item, id, createdAt: Date.now() }, ...prev].slice(0, HISTORY_LIMIT),
    );
  }, []);

  const removeToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const dismiss = useCallback((id: number) => {
    setHistory((prev) => prev.filter((n) => n.id !== id));
  }, []);

  const clear = useCallback(() => setHistory([]), []);

  const value = useMemo(
    () => ({ notify, history, dismiss, clear }),
    [notify, history, dismiss, clear],
  );

  return (
    <NotificationContext.Provider value={value}>
      <Toast.Provider swipeDirection="right" duration={4000}>
        {children}
        {toasts.map((t) => (
          <Toast.Root
            key={t.id}
            className={cn(
              "rounded-lg border bg-card p-4 shadow-lg",
              t.variant === "error" && "border-danger",
              t.variant === "success" && "border-success",
              t.variant === "info" && "border-info",
            )}
            onOpenChange={(open) => {
              if (!open) removeToast(t.id);
            }}
          >
            <div className="flex items-start gap-3">
              <div className="flex-1">
                <Toast.Title className="text-sm font-medium">
                  {t.title}
                </Toast.Title>
                {t.description && (
                  <Toast.Description className="mt-1 text-xs text-muted-foreground">
                    {t.description}
                  </Toast.Description>
                )}
              </div>
              <Toast.Close
                className="rounded-md p-1 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                aria-label="Dismiss notification"
              >
                <X className="h-3 w-3" />
              </Toast.Close>
            </div>
          </Toast.Root>
        ))}
        <Toast.Viewport className="fixed bottom-4 right-4 z-[200] flex max-w-sm flex-col gap-2" />
      </Toast.Provider>
    </NotificationContext.Provider>
  );
}

function useNotificationContext(): NotificationContextValue {
  const ctx = useContext(NotificationContext);
  if (!ctx) {
    throw new Error("useToast/useNotifications must be within ToastProvider");
  }
  return ctx;
}

/**
 * Backward-compatible transient toast hook. {@code toast(...)} raises an operation notice; existing
 * call sites keep working unchanged while the notice also lands in the NotificationCenter history.
 */
export function useToast() {
  const { notify } = useNotificationContext();
  return { toast: notify };
}

/** History-aware hook for the NotificationCenter surface. */
export function useNotifications() {
  const { history, dismiss, clear, notify } = useNotificationContext();
  return { notifications: history, dismiss, clear, notify };
}
