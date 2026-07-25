import { useProjectContext } from "@/contexts/project-context";
import { cn } from "@/lib/utils";
import * as Select from "@radix-ui/react-select";
import { ChevronDown, FolderOpen } from "lucide-react";
import { useLocation, useNavigate } from "react-router";

export function ProjectSwitcher() {
  const { projects, activeProject, isLoading } = useProjectContext();
  const navigate = useNavigate();
  const location = useLocation();

  if (isLoading) {
    return <div className="h-9 w-40 animate-pulse rounded-md bg-muted" />;
  }

  if (projects.length <= 1) {
    return null;
  }

  return (
    <Select.Root
      value={activeProject?.identifier ?? ""}
      onValueChange={(value) => {
        const project = projects.find((p) => p.identifier === value);
        if (project) {
          const match = location.pathname.match(/^\/p\/[^/]+\/(.*)/);
          const subPath = match?.[1] ?? "";
          navigate(`/p/${project.identifier}/${subPath}${location.search}`);
        }
      }}
    >
      <Select.Trigger
        className={cn(
          "inline-flex items-center gap-2 rounded-md border border-input bg-background",
          "px-3 py-1.5 text-sm font-medium",
          "hover:bg-accent hover:text-accent-foreground",
          "focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-2 focus:ring-offset-background",
        )}
      >
        <FolderOpen className="h-4 w-4 text-muted-foreground" />
        <Select.Value placeholder="Select a project" />
        <Select.Icon>
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        </Select.Icon>
      </Select.Trigger>

      <Select.Portal>
        <Select.Content
          className={cn(
            "overflow-hidden rounded-md border border-border bg-card shadow-lg",
            "animate-in fade-in-0 zoom-in-95",
          )}
          position="popper"
          sideOffset={4}
        >
          <Select.Viewport className="p-1">
            {projects.map((project) => (
              <Select.Item
                key={project.id}
                value={project.identifier}
                className={cn(
                  "relative flex cursor-pointer select-none items-center rounded-sm",
                  "px-3 py-2 text-sm outline-none",
                  "data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground",
                )}
              >
                <Select.ItemText>{project.name}</Select.ItemText>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  );
}
