import { Badge, statusVariants } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { Status } from "@/types/api";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { ChevronDown } from "lucide-react";

const validTransitions: Record<Status, Status[]> = {
  DRAFT: ["ACTIVE"],
  ACTIVE: ["DEPRECATED"],
  DEPRECATED: ["ACTIVE", "ARCHIVED"],
  ARCHIVED: [],
};

interface StatusBadgeDropdownProps {
  status: Status;
  onTransition: (newStatus: Status) => void;
  disabled?: boolean;
}

export function StatusBadgeDropdown({
  status,
  onTransition,
  disabled,
}: StatusBadgeDropdownProps) {
  const transitions = validTransitions[status];

  if (transitions.length === 0 || disabled) {
    return <Badge variant={statusVariants[status]}>{status}</Badge>;
  }

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger
        aria-label={`Change status from ${status}`}
        className={cn(
          "inline-flex cursor-pointer items-center gap-1 rounded-full",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        )}
      >
        <Badge variant={statusVariants[status]}>
          {status}
          <ChevronDown className="ml-1 h-3 w-3" />
        </Badge>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className="min-w-[120px] rounded-md border border-border bg-card p-1 shadow-lg"
          sideOffset={4}
        >
          {transitions.map((s) => (
            <DropdownMenu.Item
              key={s}
              className="flex cursor-pointer items-center rounded-sm px-2 py-1.5 text-sm outline-none hover:bg-accent focus-visible:bg-accent"
              onSelect={() => onTransition(s)}
            >
              <Badge variant={statusVariants[s]}>{s}</Badge>
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
